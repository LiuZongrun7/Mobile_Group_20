package com.mobilegroup20.modelpilot.contract.model;

/** Publisher articles are separate from official forum posts and agent evidence. */
public class NewsArticle {
    public String id;
    public String title;
    public String summary;
    public String sourceName;
    public String originalUrl;
    public String imageUrl;
    public String category;
    public long publishedAtEpochMillis;
}
