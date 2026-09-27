package com.mobilegroup20.tokentrail.game.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;

import com.mobilegroup20.tokentrail.R;
import com.mobilegroup20.tokentrail.game.engine.BoardGeometry;

/**
 * 敌人的贴图：<b>同一个人的三种动作</b>——迈步走、抡键盘砸、站着不动。
 *
 * <p>和 {@link BuildingSprites} 最大的不同：那边一种建筑一个数组（按等级查），
 * 这边**三种敌人共用这三张图**，一张都不用多。三种兵长得一样不是偷懒，
 * 是原图就是这样交付的；它们靠<b>体格</b>（{@code EnemyType.bodyCells}，
 * 三种差着 1.56 倍）和脚下那圈颜色分，见 {@link BattlefieldView#drawEnemy}。
 *
 * <h2>贴图比碰撞盒大得多，这是故意的</h2>
 *
 * <p>{@code bodyCells} 是引擎里的碰撞口径（半身位，判射程和"贴没贴上"都用它），
 * 贴图画的是**一个站着的人**，自然比脚踩的那块地高得多：体格 1.0 的那只，
 * 贴图里的人物有 1.23 格高，画布是 1.32 格见方。
 *
 * <p>所以这里多了一个 {@link #CANVAS_CELLS_PER_BODY}——"体格 1.0 的敌人，
 * 贴图占几格见方"——绘制时乘体格就是那个矩形。三个数（体格、画布、人物高）
 * 的关系在 {@code art/cut_enemy.py} 里定死，改那边就要改这边。
 *
 * <h2>画布为什么必须是正方形、而且正好 {@link #CANVAS_PX} 像素</h2>
 *
 * <p>和 {@link BuildingSprites} 校宽高比是同一个理由：比例不对不会报错，
 * 只会在游戏里被悄悄拉变形。<b>但那边校验不出"画布被整体放大了一档"</b>
 * ——等比放大不改变宽高比。这里能校验出来，因为画布是方的：
 * 边长只可能是 {@link #CANVAS_PX} 这一个数（它由"一格 256px"的设计基准算出来），
 * 出图时缩放比动过的话，边长当场就对不上了。
 *
 * <p>校验用 {@link BoardGeometry#DESIGN_CELL_PX}，是设计基准不是屏幕像素，
 * 所以和机器密度无关。
 */
final class EnemySprites {

    /**
     * 体格 1.0 的敌人，贴图占几格见方。
     *
     * <p>画布本身是方的（{@code art/cut_enemy.py} 裁出来的），所以横向纵向都是它。
     * 值是算出来的，不是挑的：原图里人物从头顶到鞋底占 993 行里的 922 行，
     * 想让体格 1.0 的那只有 1.2 格高，画布就得是 {@code 1.2 / (922/993) ≈ 1.29}。
     * 取 1.32 是留一点余量——举起来抡的键盘和甩出去的电弧都比人宽，
     * 画布太紧会把它们裁掉，而 {@code cut_enemy.py} 里那个框按的就是这个数。
     */
    static final float CANVAS_CELLS_PER_BODY = 1.32f;

    /**
     * 出图时的最大体格，也就是 {@code EnemyType.BRUTE.bodyCells}。
     *
     * <p><b>画布按最大那只裁，是因为贴图只能缩不能放。</b>按体格 1.0 裁的话，
     * 重甲那个矩形比贴图还大 25%，放上去就糊了（{@link BoardGeometry#DESIGN_CELL_PX}
     * 那一整段讲的"任何机型放到最大都是缩小显示"，靠的就是出图留够余量）。
     * 杂兵和快兵都是往下缩，缩小不损失画质。
     *
     * <p>和 {@code EnemyType.BRUTE} 是一对，改那边要改这边和 {@code cut_enemy.py} 的
     * {@code ART_BODY_CELLS}。
     */
    private static final float ART_BODY_CELLS = 1.25f;

    /** 贴图边长（px）。出图脚本里那个 {@code CANVAS_PX} 必须等于它。 */
    static final int CANVAS_PX = Math.round(
            CANVAS_CELLS_PER_BODY * ART_BODY_CELLS * BoardGeometry.DESIGN_CELL_PX);

    private final Bitmap stand;
    private final Bitmap walk;
    private final Bitmap attack;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    EnemySprites(Context context) {
        stand = decode(context, "站姿", R.drawable.enemy_stand);
        walk = decode(context, "走姿", R.drawable.enemy_walk);
        attack = decode(context, "挥击", R.drawable.enemy_attack);
    }

    /** 站着不动。走路循环里的"过渡帧"，也是抡下去之前那一下收势。 */
    Bitmap stand() {
        return stand;
    }

    /** 迈步走，脚下带尘土。没被挡住的时候用。 */
    Bitmap walk() {
        return walk;
    }

    /** 抡键盘砸，甩出一道青色电弧。被挡住啃建筑的时候用。 */
    Bitmap attack() {
        return attack;
    }

    private static Bitmap decode(Context context, String what, int resId) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        // drawable-nodpi 本来就不带密度，但 decodeResource 仍可能按屏幕密度缩，
        // 缩完就和"一格 256"对不上了。自己按格算，不让系统插手。
        options.inScaled = false;

        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), resId, options);
        if (bitmap == null) {
            throw new IllegalStateException(
                    "敌人的" + what + "贴图读不出来（resId=" + resId + "）");
        }
        // 正方形 + 边长正好是设计基准算出来的那个数，两条都要。
        // 只校"是不是方的"挡不住整体缩放（等比缩放不改变比例），
        // 所以要连边长一起对——见类注释。
        if (bitmap.getWidth() != CANVAS_PX || bitmap.getHeight() != CANVAS_PX) {
            throw new IllegalArgumentException(String.format(
                    "敌人的%s贴图应该是 %d×%d（体格 %.2f 的画布），实际 %d×%d；"
                            + "尺寸不对不会报错，只会在游戏里被缩放——"
                            + "多半是 art/cut_enemy.py 里的 CANVAS_PX 或那里的裁切框改过了",
                    what, CANVAS_PX, CANVAS_PX, ART_BODY_CELLS,
                    bitmap.getWidth(), bitmap.getHeight()));
        }
        return bitmap;
    }

    /** 画贴图用的笔：开双线性过滤，缩放时不会出锯齿。 */
    Paint paint() {
        return paint;
    }
}
