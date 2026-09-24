package com.mobilegroup20.tokentrail.contract.model;

/**
 * 客户端问建议 agent 的一句话。
 *
 * <p>请求里<b>不带 uid</b>：uid 由服务端从 Firebase ID token 里解出来。
 * 让客户端传 uid 等于把「查谁的数据」交给客户端决定，那样账号隔离就是摆设。
 * 服务端收到请求先验 token，验过了才拿 token 里的 uid 去查库。
 */
public class AdviceRequest {

    /** 用户提的问题原话。 */
    public String question;

    /**
     * 界面上选的界别，比如当前在哪个赛季/哪个月。只是给模型的上下文提示，
     * 不作为查询条件——真正的日期范围由服务端按工具参数决定。
     */
    public String seasonId;

    public AdviceRequest() {
    }

    public AdviceRequest(String question, String seasonId) {
        this.question = question;
        this.seasonId = seasonId;
    }
}
