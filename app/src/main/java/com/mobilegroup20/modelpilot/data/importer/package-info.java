/**
 * 导入数据：把一份<b>导出的文件</b>读回本机库——「换手机」这件事的另一半。
 *
 * <p>导出（{@code data/export}）解决「能带走」，本包解决「换回来」。没有它，换台手机之后
 * 用户手上有个文件，而 App 里空空如也。
 *
 * <p>一条链路，四个环节，各自只干一件事：
 * <pre>
 *   ExportFileReader（认格式：JSON 还是 zip）
 *        → ExportJsonParser / ExportZipParser（读成 ImportBundle，格式无关）
 *             → DataImporter（合并：按 id 认亲、本机优先、坏行报出来）
 *                  → ImportTarget → RoomImportTarget（只写库，不判断）
 * </pre>
 *
 * <p><b>为什么中间要夹一个格式无关的 {@link com.mobilegroup20.modelpilot.data.importer.ImportBundle}</b>：
 * JSON 与 CSV 读出来的东西形状差很多（CSV 是扁平的，附件只剩文件名、工具调用根本没列），
 * 但"怎么合并"对两者是同一件事。夹这一层之后，合并逻辑只有一份、也只需要一套测试。
 *
 * <p>四条硬要求（每一条都有单测钉着，见 {@code DataImporterTest} / {@code CsvReaderTest} /
 * {@code ExportParserRoundTripTest}）：
 * <ul>
 *   <li><b>按 id 认亲，不按名字。</b>同一个文件导两次，第二次一条都不新增。</li>
 *   <li><b>本机优先是默认。</b>手机上的对话是活的，文件里的是存档；要覆盖得用户自己选
 *       （{@link com.mobilegroup20.modelpilot.data.importer.MergePolicy}）。
 *       导入<b>从不删东西</b>——"恢复备份"不该顺手清掉手机上别的东西。</li>
 *   <li><b>不补空、不编造。</b>文件里没有的字段就是"不知道"，绝不写成 0 或 {@code "unknown"}，
 *       这是导出那边「不知道就留空」的反向一半。</li>
 *   <li><b>坏行绝不静默。</b>读不动的行进 {@code ImportSummary.problems} 并在界面上报出来——
 *       少导进来的东西没有提示，用户就会以为数据齐了。</li>
 * </ul>
 *
 * <p>往返测试是拿<b>真的导出器</b>吐出来的字节喂给本包（{@code ExportParserRoundTripTest}）：
 * 两边任何一处改了字段名，那个测试立刻红。文件格式与全部取舍见 {@code docs/EXPORT.md}。
 *
 * <p>用量那一段最终落在 {@code data/local/CallImporter}（按主键去重 + 重滚当天汇总），
 * 对话那三张表走 {@code ChatDao}。
 *
 * @see com.mobilegroup20.modelpilot.data.export.DataExporter
 */
package com.mobilegroup20.modelpilot.data.importer;
