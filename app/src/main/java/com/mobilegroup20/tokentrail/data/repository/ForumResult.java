package com.mobilegroup20.tokentrail.data.repository;

/** A failed request is not an empty feed. */
public final class ForumResult<T> {
    public enum Status { LOADING, SUCCESS, ERROR }
    public final Status status;
    public final T data;
    public final String code;
    private ForumResult(Status status, T data, String code) {
        this.status = status;
        this.data = data;
        this.code = code;
    }
    public static <T> ForumResult<T> loading() { return new ForumResult<>(Status.LOADING, null, null); }
    public static <T> ForumResult<T> success(T data) { return new ForumResult<>(Status.SUCCESS, data, null); }
    public static <T> ForumResult<T> error(String code) { return new ForumResult<>(Status.ERROR, null, code); }
}
