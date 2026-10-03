/**
 * 数据面板，四个部分：Activity（每日活跃热力图）、Sessions（按会话拆分）、
 * Compare（跨提供方成本对比）、Budget（月度预算与警告）。
 * <b>负责人：数据侧（张莉），图表复用见大纲 §8。</b>
 *
 * <p>图表用 MPAndroidChart，热力图自己画。所有查询都走
 * {@link com.mobilegroup20.modelpilot.data.repository.UsageRepository}。
 *
 * <p><b>依赖记录的部分在没有数据时要显示空状态，不能显示 0。</b>
 * 「这天没花钱」和「这天没记录」是两件事，画成一样的柱子会让人得出相反结论。
 * 缺失的日期由 `DashboardUsage` 自己按「这个月已经过完的天」算（`daysWithRecords`）。
 *
 * <p>所有金额都标明是估算，并能点回费率来源页面。
 */
package com.mobilegroup20.modelpilot.ui.dashboard;
