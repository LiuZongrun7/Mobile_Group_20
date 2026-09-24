package com.mobilegroup20.tokentrail.data.repository;

import androidx.lifecycle.LiveData;

import com.mobilegroup20.tokentrail.contract.model.AdviceAnswer;
import com.mobilegroup20.tokentrail.contract.model.AdviceRequest;

/**
 * 建议 agent 的调用口。<b>游戏侧（刘宗润）实现，界面只依赖这个接口。</b>
 *
 * <p>实现里做三件事：拿当前登录用户的 Firebase ID token、请求服务端、把结果转成
 * {@link AdviceAnswer}。真正的模型调用和工具循环在服务端，客户端<b>不持有</b>任何
 * 模型 API key——key 只在服务端，这是大纲 §8 明写的。
 *
 * <p>请求里不带 uid：服务端从 ID token 里解。见 {@link AdviceRequest}。
 */
public interface AdviceRepository {

    /**
     * 问一句，拿一次回答。
     *
     * <p>返回的 LiveData 在没有结果之前是 null，界面用「等待中」状态渲染。
     * 失败不回退成空 {@link AdviceAnswer}——空回答会和「查到了，但没有数据」
     * 混在一起，用户分不清是网络断了还是真没记录。
     */
    LiveData<AdviceAnswer> ask(AdviceRequest request);

    /**
     * agent 自己这个月花掉的钱，微美元。
     *
     * <p>单独一个方法是因为这部分开销要<b>和编码 agent 的用量分开统计</b>
     * （大纲 §5）：问 agent 花了多少钱，不能把 agent 自己的账单算进去，
     * 否则用户会以为自己的编码开销涨了。
     */
    LiveData<Long> ownCostThisMonth(String uid, String month);
}
