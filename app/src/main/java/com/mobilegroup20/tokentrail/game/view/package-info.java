/**
 * 战场的绘制与触摸：网格、塔、城墙、核心、来袭单位、放置与升级操作。
 * <b>负责人：刘宗润。</b>
 *
 * <p>用自定义 View + Canvas 画，不用一张张 ImageView 拼：战场上同时有几十个格子、
 * 十几座塔和一波单位，用布局控件会又卡又难对齐；Canvas 里坐标就是数字，
 * 和 {@code game.engine} 的网格坐标一一对应。
 *
 * <p>这一层<b>只做「把状态画出来」和「把触摸换成引擎调用」</b>，
 * 不放任何游戏规则。判断「这里能不能放塔」是引擎的事，View 只问结果。
 * 这样改配色和改平衡互不影响。
 *
 * <p>两个类，分工按「谁在底下」分：
 *
 * <ul>
 *   <li>{@link MountainBackgroundView}
 *       ——整页的岩石底，铺满屏幕、包括状态栏和手势条底下。它画的是<b>地形</b>
 *       而不是背景色，和战场右边那三格山区是<b>同一张贴图、同一个锚点、
 *       同一个 Paint</b>（{@link RockTexture}）。
 *       界面（HUD、按钮、底部导航）浮在它上面，读起来是"一张连续的山景上
 *       浮着一层控件"，而不是"一个界面里嵌了一块游戏"。<b>它和战场共用
 *       同一个 Viewport</b>（由 {@link BattlefieldView} 通过
 *       {@code CameraListener} 推过来），所以拖动和缩放时上下的石头跟着一起动——
 *       背景钉死的话一眼就能看出石头是画在界面上的，不是世界里的；</li>
 *   <li>{@link BattlefieldView}
 *       ——世界层，画地形、格子、建筑、敌人，并处理缩放/平移/点击。</li>
 * </ul>
 *
 * <p><b>两个 View 都铺满全屏</b>（{@code match_parent}），"战场"不再由某个
 * View 的边界表示，而是由 {@code activity_main.xml} 里那个夹在 HUD 和按钮行
 * 中间的空占位 {@code @id/play_area}。铺满是必须的：真机上 36 行地不放大就有
 * 1915px，放大到 2 倍是 3830px、比整块屏幕（2844px）还高，本来就该糊满整屏；
 * 世界层只占中间一条的话地形会被裁掉，放到多大都出不了那个框，
 * 屏幕上永远是"石头/绿地/石头"三段。
 * 格子多大、相机怎么取景、事件坐标怎么算，**全部以 play_area 为准**
 * （{@link BattlefieldView#setPlayArea} / {@link MountainBackgroundView#setPanel}）。
 *
 * <p><b>配色是固定美术，不跟随系统深浅色。</b>整页的底是一张岩石贴图，
 * 白天黑夜都是它；跟着主题翻色只会让同一张图在两种模式下都难看。
 * 所以 {@code res/values-night/} 下没有游戏页的覆盖文件，
 * 压在岩石上的文字也不能用 {@code ?attr/colorOnSurface}（浅色模式下那是近黑色，
 * 压在深岩石上等于看不见），要用 {@code colors.xml} 里的
 * {@code on_mountain} / {@code on_mountain_dim}（对岩石底的对比度都过 WCAG AA）。
 * 悬浮控件统一用三个 {@code glass} 透明度，表示三种"浮起来的高度"。
 * 完整规则见 {@code docs/ART.md} §5。
 */
package com.mobilegroup20.tokentrail.game.view;
