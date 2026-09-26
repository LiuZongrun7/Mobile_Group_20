/**
 * 日志导入：把 Codex / ZCode / DSH 的用量日志读进来，变成
 * {@link com.mobilegroup20.tokentrail.contract.model.UsageCall}。<b>负责人：张莉。</b>
 *
 * <p>每家一个解析器，都实现同一个接口，导入流程不关心是谁的日志：
 * <pre>
 *   LogParser  →  List&lt;UsageCall&gt;（source = IMPORTED） →  UsageRepository.importCalls
 * </pre>
 *
 * <p>这里是**解析**那一半。用量进系统的入口有两条：本地日志（本包）和 provider API
 * 拉取（{@code data/remote/} 的 fetcher）。<b>fetcher 不自己解析</b>——它只负责把字节
 * 弄到手（DeepSeek 那个控制台导出接口返回的就是同一个 zip），拿到之后仍然交给本包的
 * 解析器，所以两条路共用同一份解析代码。见 {@code docs/DATA_SOURCES.md}。
 *
 * <p>三条硬要求：
 * <ul>
 *   <li><b>id 必须由记录内容算出来</b>，不能随机生成，且要带 {@code call:} / {@code bucket:}
 *       前缀（算法见 {@link com.mobilegroup20.tokentrail.contract.model.UsageCall#id}）。
 *       同一份数据进来两次必须一条都不新增，这是验收要测的行为。</li>
 *   <li>推理模型的思考 token <b>并入 output</b>，不能丢——provider 是按输出计费的。</li>
 *   <li>解析失败的行要计入 {@code ImportResult.rejected} 并留下原因，
 *       不要静默跳过：少算的用量没有任何提示，用户会以为账单出错了。</li>
 * </ul>
 *
 * <p>演示用的样例数据在这里生成，标记 {@code Source.SAMPLE}，
 * <b>只有 IMPORTED 才进日汇总</b>，所以样例数据不会影响游戏资源和预算。
 *
 * @see com.mobilegroup20.tokentrail.util.TimeUtils
 */
package com.mobilegroup20.tokentrail.data.importer;
