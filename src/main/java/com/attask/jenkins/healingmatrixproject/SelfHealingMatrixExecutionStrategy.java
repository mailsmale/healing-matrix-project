package com.attask.jenkins.healingmatrixproject;

import hudson.AbortException;
import hudson.Extension;
import hudson.Util;
import hudson.console.ModelHyperlinkNote;
import hudson.matrix.*;
import hudson.matrix.Messages;
import hudson.matrix.listeners.MatrixBuildListener;
import hudson.model.*;
import hudson.model.Queue;
import hudson.util.ListBoxModel;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.export.Exported;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Almost the same as {@link DefaultMatrixExecutionStrategyImpl} with a few changes.
 * <ul>
 * <li>When a job fails, it will automatically be rerun a user-defined number of times.</li>
 * <li>
 * With the default implementation, it waits for the builds to finish in order.
 * If one is still building, it will move on to the next one and find the fastest builds to report on first.
 * This gives us the ability to immediately retry failed builds if the first build is slower than the others.
 * </li>
 * <li>
 * You cannot currently run jobs "Sequentially" and there is no "Touchstone" build.
 * This is considered a shortcoming and may be implemented in the future.
 * </li>
 * </ul>
 * <p/>
 * User: Joel Johnson
 * Date: 3/6/13
 * Time: 10:28 AM
 */
public class SelfHealingMatrixExecutionStrategy extends MatrixExecutionStrategy {
	private static final Logger LOGGER = Logger.getLogger("healing-matrix-project");

	private final String logPattern;
	private final Result worseThanOrEqualTo;
	private final Result betterThanOrEqualTo;
	private final int maxRetries;
	private final boolean stopRetryingAfterOneFails;

	@Deprecated @SuppressWarnings("UnusedDeclaration") private final transient int quietPeriodSeconds = 0; // Backwards compatibility

	@DataBoundConstructor
	public SelfHealingMatrixExecutionStrategy(String logPattern, Result worseThanOrEqualTo, Result betterThanOrEqualTo, int maxRetries, boolean stopRetryingAfterOneFails) {
		this.logPattern = logPattern == null ? "" : logPattern;
		this.worseThanOrEqualTo = worseThanOrEqualTo == null ? Result.FAILURE : worseThanOrEqualTo;
		this.betterThanOrEqualTo = betterThanOrEqualTo == null ? Result.ABORTED : betterThanOrEqualTo.isWorseOrEqualTo(this.worseThanOrEqualTo) ? betterThanOrEqualTo : this.worseThanOrEqualTo;
		this.maxRetries = maxRetries < 0 ? 1 : maxRetries;
		this.stopRetryingAfterOneFails = stopRetryingAfterOneFails;
	}

	/**
	 * Log pattern is a line-separated list of regular expression patterns.
	 * It is used to determine if a build can be rerun.
	 * If any line in the log matches any of these patterns, then the build will be rerun.
	 * If null or empty, then it is assumed to always rerun if {@link #getWorseThanOrEqualTo()} matches the build result.
	 */
	@Exported
	public String getLogPattern() {
		return logPattern;
	}

	/**
	 * Only builds whose results are worse-than or equal-to this value.
	 */
	@Exported
	public Result getWorseThanOrEqualTo() {
		return worseThanOrEqualTo;
	}

	/**
	 * Only builds whose results are better-than or equal-to this value.
	 * Useful for ignoring builds that are canceled or otherwise aborted.
	 */
	@Exported
	public Result getBetterThanOrEqualTo() {
		return betterThanOrEqualTo;
	}

	/**
	 * The number of times failure should be retried.
	 * This helps prevent builds from being infinitely stuck.
	 * Default is '1'. Which means a given matrix run could potentially run 2 times (the initial and 1 retry).
	 */
	@Exported
	public int getMaxRetries() {
		return maxRetries;
	}

	@Exported
	public boolean getStopRetryingAfterOneFails() {
		return stopRetryingAfterOneFails;
	}

	@Override
	public Result run(MatrixBuild.MatrixBuildExecution execution) throws InterruptedException, IOException {
		if (!notifyStartBuild(execution.getAggregators(), execution.getListener())) {
			return Result.FAILURE;
		}

		List<Pattern> patterns = createPatternsList();

		Map<MatrixConfiguration, Integer> retries = new HashMap<MatrixConfiguration, Integer>();
		LinkedList<MatrixConfiguration> runningConfigurations = scheduleMatrixRuns(execution, retries);
		return waitForMatrixRuns(execution, patterns, retries, runningConfigurations);
	}

	/**
	 * Schedules the initial runs of the matrix runs.
	 *
	 * @param execution Provided by the plugin.
	 * @param retries   Mutable map that is used to track the number of times a configuration has been run.
	 *                  The map is populated with '0' for every configuration scheduled.
	 * @return List of configurations scheduled.
	 *         This is a subset of the configurations passed in the execution field, since plugins can reject specific axises from running.
	 */
	private LinkedList<MatrixConfiguration> scheduleMatrixRuns(MatrixBuild.MatrixBuildExecution execution, Map<MatrixConfiguration, Integer> retries) throws InterruptedException {
		MatrixBuild build = (MatrixBuild) execution.getBuild();
		LinkedList<MatrixConfiguration> configurations = new LinkedList<MatrixConfiguration>();
		for (MatrixConfiguration configuration : execution.getActiveConfigurations()) {
			if (MatrixBuildListener.buildConfiguration(build, configuration)) {
				int defaultRetriedCount = 0;
				retries.put(configuration, defaultRetriedCount);
				configurations.add(configuration);
				scheduleConfigurationBuild(execution, configuration, new Cause.UpstreamCause((Run) build));
			}
		}
		return configurations;
	}

	/**
	 * Waits for the given configurations to finish, retrying any that qualify to be rerun.
	 *
	 * @param execution      Provided by the plugin.
	 * @param patterns       List of regular expression patterns used to scan the log to determine if a build should be rerun.
	 * @param retries        Mutable map that tracks the number of times a specific configuration has been retried.
	 * @param configurations The configurations that have already been scheduled to run that should be waited for to finish.
	 * @return The worst result of all the runs. If a build was rerun, only the result of the rerun is considered.
	 * @throws InterruptedException
	 * @throws IOException
	 */
	private Result waitForMatrixRuns(MatrixBuild.MatrixBuildExecution execution, List<Pattern> patterns, Map<MatrixConfiguration, Integer> retries, LinkedList<MatrixConfiguration> configurations) throws InterruptedException, IOException {
		BuildListener listener = execution.getListener();
		PrintStream logger = listener.getLogger();

		Map<String, String> whyBlockedMap = new HashMap<String, String>();//keep track of why builds are blocked so we can print unique messages when they change.
		Result finalResult = Result.SUCCESS;
		int iteration = 0;
		boolean continueRetrying = true;
		while (!configurations.isEmpty()) {
			++iteration;
			MatrixConfiguration configuration = configurations.removeFirst();
			if (isBuilding(execution, configuration, whyBlockedMap)) {
				if (iteration >= configurations.size()) {
					//Every time we loop through all the configurations, sleep for a bit.
					//This is to prevent polling too often while everything is still building.
					iteration = 0;
					Thread.sleep(1000);
				}
				configurations.add(configuration);
				continue;
			}

			Run parentBuild = execution.getBuild();
			MatrixRun matrixRun = configuration.getBuildByNumber(parentBuild.getNumber());
			Result runResult = matrixRun.getResult();
			if (continueRetrying && runResult.isWorseOrEqualTo(getWorseThanOrEqualTo()) && runResult.isBetterOrEqualTo(getBetterThanOrEqualTo())) {
				if (matchesPattern(matrixRun, patterns)) {
					int retriedCount = retries.get(configuration);
					if (retriedCount < getMaxRetries()) {
						++retriedCount;
						retries.put(configuration, retriedCount);
						//rerun
						String logMessage = String.format("%s was %s. Matched pattern to rerun. Rerunning (%d).", matrixRun, runResult, retriedCount);
						listener.error(logMessage);

						HealedAction action = parentBuild.getAction(HealedAction.class);
						if(action == null) {
							//noinspection SynchronizationOnLocalVariableOrMethodParameter
							synchronized (parentBuild.getActions()) {
								action = parentBuild.getAction(HealedAction.class);
								if(action == null) {
									action = new HealedAction(matrixRun.getCharset());
									parentBuild.addAction(action);
								}
							}
						}
						action.addAutoHealedJob(matrixRun);

						MatrixConfiguration parent = matrixRun.getParent();
						if(parent != null) {
							//I'm paranoid about NPEs
							parent.removeRun(matrixRun);
							matrixRun.delete();
						} else {
							LOGGER.severe("couldn't remove old run, parent was null. This is a Jenkins core bug.");
						}
						scheduleConfigurationBuild(execution, configuration, new SelfHealingCause(parentBuild, retriedCount));
						configurations.add(configuration);
						continue;
					} else {
						String logMessage = String.format("%s was %s. Matched pattern to rerun, but the max number of retries (%d) has been met.", matrixRun, runResult, getMaxRetries());
						listener.error(logMessage);
						if(getStopRetryingAfterOneFails()) {
							listener.error("Not retrying any more builds.");
							continueRetrying = false;
						}
					}
				} else {
					String logMessage = String.format("%s was %s. It did not match the pattern to rerun. Accepting result.", matrixRun, runResult);
					logger.println(logMessage);
				}
			}
			notifyEndRun(matrixRun, execution.getAggregators(), execution.getListener());
			finalResult = finalResult.combine(runResult);
		}
		return finalResult;
	}

	/**
	 * Logic is more-or-less copied from {@link DefaultMatrixExecutionStrategyImpl#notifyStartBuild(java.util.List)}
	 *
	 * Triggers the startBuild event on all aggregators.
	 * This should be called before any run is started.
	 *
	 *
	 * @param aggregators The aggregators to be notified.
	 * @param listener Listener from parent build that can be logged to.
	 * @return True if all aggregators return true. If any aggregator returns false, false is immediately returned and no new aggregators are called.
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private boolean notifyStartBuild(List<MatrixAggregator> aggregators, BuildListener listener) throws IOException, InterruptedException {
		for (MatrixAggregator aggregator : aggregators) {
			if(!aggregator.startBuild()) {
				listener.error("Aggregator terminated build: " + aggregator.toString());
				return false;
			}
		}
		return true;
	}

	/**
	 * Logic is copied from {@link DefaultMatrixExecutionStrategyImpl#notifyEndBuild(hudson.matrix.MatrixRun, java.util.List)}
	 */
	private void notifyEndRun(MatrixRun run, List<MatrixAggregator> aggregators, BuildListener listener) throws InterruptedException, IOException {
		if (run==null)    return; // can happen if the configuration run gets cancelled before it gets started.
		for (MatrixAggregator aggregator : aggregators) {
			if(!aggregator.endRun(run)) {
				listener.error("Aggregator terminated build: " + aggregator.toString());
				throw new AbortException();
			}
		}
	}

	/**
	 * Checks if the logs of the given run match any of the given patterns, line-by-line.
	 *
	 * @param matrixRun The run to be considered.
	 * @param patterns  The patterns to match with.
	 * @return True if at least one line of the logs match at least one of the given patterns.
	 * @throws IOException If there's a problem reading the log file.
	 */
	private boolean matchesPattern(MatrixRun matrixRun, List<Pattern> patterns) throws IOException {
		if (patterns == null || patterns.isEmpty()) {
			return true; //No specific patterns specified. Accept everything.
		}

		BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(matrixRun.getLogFile()), matrixRun.getCharset()));
		try {
			for (String line = reader.readLine(); line != null; line = reader.readLine()) {
				for (Pattern pattern : patterns) {
					Matcher matcher = pattern.matcher(line);
					if (matcher.find()) {
						return true;
					}
				}
			}
		} finally {
			reader.close();
		}
		return false;
	}

	/**
	 * Compiles all the patterns from {@link #getLogPattern()}
	 */
	private List<Pattern> createPatternsList() {
		String logPattern = getLogPattern();
		if (logPattern == null || logPattern.isEmpty()) {
			return Collections.emptyList();
		}
		List<Pattern> result = new LinkedList<Pattern>();
		Scanner scanner = new Scanner(logPattern);
		while (scanner.hasNextLine()) {
			String line = scanner.nextLine();
			Pattern pattern = Pattern.compile(line);
			result.add(pattern);
		}
		return result;
	}

	/**
	 * Determines if the given configuration is currently running.
	 * If any of the configurations are currently stuck in the queue, it is logged.
	 *
	 * @param execution     Contains information about the general build, including the listener used to log queue blockage.
	 * @param configuration The configuration being checked to see if it's running.
	 * @param mutableWhyMap Mutable map used to track the reasons a configuration is stuck in the queue.
	 *                      This prevents duplicate reasons from flooding the logs.
	 * @return True if the build represented by the given configuration is currently running or stuck in the queue.
	 *         False if the build has finished running.
	 */
	private boolean isBuilding(MatrixBuild.MatrixBuildExecution execution, MatrixConfiguration configuration, Map<String, String> mutableWhyMap) {
		MatrixRun build = configuration.getBuildByNumber(execution.getBuild().getNumber());
		if (build != null) {
			return build.isBuilding();
		}

		Queue.Item queueItem = configuration.getQueueItem();
		if (queueItem != null) {
			String why = queueItem.getWhy();
			String key = queueItem.task.getFullDisplayName() + " " + queueItem.id;
			String oldWhy = mutableWhyMap.get(key);
			if (why == null) {
				mutableWhyMap.remove(key);
			}
			if (why != null && !why.equals(oldWhy)) {
				mutableWhyMap.put(key, why);
				BuildListener listener = execution.getListener();
				PrintStream logger = listener.getLogger();
				logger.print("Configuration " + ModelHyperlinkNote.encodeTo(configuration) + " is still in the queue: ");
				queueItem.getCauseOfBlockage().print(listener); //this is still shown on the same line
			}
		}

		return true;
	}

	/**
	 * Schedules the given configuration.
	 *
	 * Copied from the {@link DefaultMatrixExecutionStrategyImpl#scheduleConfigurationBuild(hudson.matrix.MatrixBuild.MatrixBuildExecution, hudson.matrix.MatrixConfiguration)}
	 *
	 * @param execution     Contains information about the general build, including the listener used to log queue blockage.
	 * @param configuration The configuration to schedule.
	 * @param upstreamCause The cause of the build. Will either be an {@link hudson.model.Cause.UpstreamCause} or {@link com.attask.jenkins.healingmatrixproject.SelfHealingCause}.
	 */
	private void scheduleConfigurationBuild(MatrixBuild.MatrixBuildExecution execution, MatrixConfiguration configuration, Cause.UpstreamCause upstreamCause) throws InterruptedException {
		MatrixBuild build = (MatrixBuild) execution.getBuild();
		execution.getListener().getLogger().println(Messages.MatrixBuild_Triggering(ModelHyperlinkNote.encodeTo(configuration)));

		// filter the parent actions for those that can be passed to the individual jobs.
		List<MatrixChildAction> childActions = Util.filter(build.getActions(), MatrixChildAction.class);

		BuildListener listener = execution.getListener();
		while(!configuration.scheduleBuild(childActions, upstreamCause)) {
			String msg = "Unable to schedule build " + configuration.getFullDisplayName() + ". Retrying.";
			listener.error(msg);
			Thread.sleep(500);
		}
	}

	@Extension
	public static class DescriptorImpl extends MatrixExecutionStrategyDescriptor {
		@Override
		public String getDisplayName() {
			return "Self Healing";
		}

		@SuppressWarnings("UnusedDeclaration")
		public ListBoxModel doFillWorseThanOrEqualToItems() {
			ListBoxModel items = new ListBoxModel();
			items.add("Success", Result.SUCCESS.toString());
			items.add("Unstable", Result.UNSTABLE.toString());
			items.add("Failure", Result.FAILURE.toString());
			items.add("Not Built", Result.NOT_BUILT.toString());
			items.add("Aborted", Result.ABORTED.toString());
			return items;
		}

		@SuppressWarnings("UnusedDeclaration")
		public ListBoxModel doFillBetterThanOrEqualToItems() {
			return doFillWorseThanOrEqualToItems();
		}
	}
}
