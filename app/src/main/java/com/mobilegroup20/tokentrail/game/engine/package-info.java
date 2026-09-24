/**
 * 塔防的游戏逻辑：建造网格、防御塔、城墙、核心、波次、资源换算与结算。
 * <b>负责人：刘宗润。</b>
 *
 * <p><b>这个包不 import 任何 android.* 的类。</b>这不是洁癖，是为了两件事：
 * <ul>
 *   <li>可以用纯 JUnit 测（{@code app/src/test}，不需要模拟器）。波次推进、
 *       伤害结算、资源换算这些是最该有测试的地方，一旦依赖 Android 就只能靠手点；</li>
 *   <li>渲染（{@code game.view}）和逻辑分开，改画面不会碰坏平衡。</li>
 * </ul>
 *
 * <p>时间的推进由外面把「当前时刻」传进来，逻辑内部不调 {@code System.currentTimeMillis()}，
 * 这样测试可以一秒钟跑完一整个赛季。
 *
 * <p>规则参照大纲 §5：敌人<b>只从一侧进入</b>；防御塔和城墙由玩家在网格上自行放置；
 * 资源按每 100 万 token 换 100 个，三类 token 换三类互不通兑的资源；
 * 波次由用户手动发起、实时推进，不做离线模拟。
 *
 * <p>结算规则见 {@link com.mobilegroup20.tokentrail.contract.model.SeasonState} 的类注释。
 */
package com.mobilegroup20.tokentrail.game.engine;
