package com.mobilegroup20.tokentrail.game.engine;

import java.util.Objects;

/**
 * 一个格子坐标。<b>列 0 在最左，行 0 在最上</b>（和屏幕坐标一致，y 向下）。
 *
 * <p>游戏逻辑里到处要用它当 key（塔放在哪、敌人走到哪、哪格是墙），所以实现了
 * {@link #equals}/{@link #hashCode}，可以直接放进 {@code HashMap} 或
 * {@code HashSet}。
 *
 * <p><b>不要用像素坐标当 key。</b>像素只属于渲染层，同一个格子在两台手机上
 * 的像素值不一样，拿它做 key 会写出「换设备就出错」的代码。
 *
 * <p>对象是只读的，可以放心共享。
 */
public final class Cell {

    public final int col;
    public final int row;

    public Cell(int col, int row) {
        this.col = col;
        this.row = row;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Cell)) {
            return false;
        }
        Cell other = (Cell) o;
        return col == other.col && row == other.row;
    }

    @Override
    public int hashCode() {
        return Objects.hash(col, row);
    }

    @Override
    public String toString() {
        return "(" + col + "," + row + ")";
    }
}
