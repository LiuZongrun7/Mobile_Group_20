package com.mobilegroup20.modelpilot.contract.model;
import java.util.ArrayList;
import java.util.List;
/** GET forum/trending: counts of reports and community interactions, not fabricated view counts. */
public class ForumTrending {
    public int windowDays;
    public long asOfEpochMillis;
    public List<TrendingTopic> topics = new ArrayList<>();
    public List<ForumPost> posts = new ArrayList<>();
}
