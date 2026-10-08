package com.mobilegroup20.modelpilot.contract.model;
/** Matches the news topic contract documented in docs/FORUM_API.md. */
public class TrendingTopic {
    public String name;
    public String query;
    public int rank;
    public int articleCount;
    public int sourceCount;
    public long latestPublishedAtEpochMillis;
}
