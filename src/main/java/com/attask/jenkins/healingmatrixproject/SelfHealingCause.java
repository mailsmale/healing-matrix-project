package com.attask.jenkins.healingmatrixproject;

import hudson.model.Cause;
import hudson.model.Run;

/**
 * Describes the cause of a build that has "Self-Healed".
 * No different than an Upstream cause other than it appends the number of attempts that have been made to the description.
 * <p/>
 * User: Joel Johnson
 * Date: 3/6/13
 * Time: 12:26 PM
 */
public class SelfHealingCause extends Cause.UpstreamCause {
	private final int retryCount;

	public SelfHealingCause(Run<?, ?> up, int retryCount) {
		super(up);
		this.retryCount = retryCount;
	}

	public int getRetryCount() {
		return retryCount;
	}

	@Override
	public String getShortDescription() {
		return super.getShortDescription() + " (Self Healed #" + getRetryCount() + ")";
	}
}
