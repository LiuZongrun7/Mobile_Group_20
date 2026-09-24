/**
 * 游戏内的建议 agent 面板。<b>负责人：刘宗润。</b>
 *
 * <p>面板本身只是聊天界面；真正需要注意的是它怎么把结果呈现出来：
 * {@link com.mobilegroup20.tokentrail.contract.model.AdviceAnswer} 带的三样东西
 * <b>必须都能点开看到</b>——证据、调用了哪些工具、缺哪些数据。
 * 藏起来就等于没有，项目「答案来自证据」那句话也就落空了。
 *
 * <p>这里还负责工具调用的展示与本地分发：客户端侧的 {@code ToolInvoker} 把
 * {@link com.mobilegroup20.tokentrail.contract.tool.AgentTool} 映射到对应的 Repository，
 * 供本地预演和调试用。正式流程里工具循环在服务端跑，客户端只收最终回答。
 *
 * <p>agent 自己的 API 开销要<b>和编码 agent 的用量分开统计</b>，见
 * {@link com.mobilegroup20.tokentrail.data.repository.AdviceRepository#ownCostThisMonth}。
 */
package com.mobilegroup20.tokentrail.agent;
