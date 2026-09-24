package com.mobilegroup20.tokentrail.data.repository;

import androidx.lifecycle.LiveData;

import com.mobilegroup20.tokentrail.contract.model.Budget;
import com.mobilegroup20.tokentrail.contract.tool.BudgetStatus;

/**
 * 预算的读写口。<b>数据侧（张莉）实现。</b>
 *
 * <p>这个接口只管「上限」，不管「已花多少」。花销永远从用量现算
 * （见 {@link com.mobilegroup20.tokentrail.contract.model.Budget} 的类注释），
 * 所以 {@link #status} 返回的是「上限 + 现算花销」的组合。
 */
public interface BudgetRepository {

    /** 取某个月的预算设置。没设过返回 null，界面据此显示「还没设预算」的引导。 */
    LiveData<Budget> budgetOf(String uid, String month);

    /** 设置或修改预算。month 已经存在就覆盖。 */
    LiveData<Budget> saveBudget(Budget budget);

    /** 给 agent 的 {@code getBudgetStatus} 用，也给 dashboard 的预算卡片用。 */
    LiveData<BudgetStatus> status(String uid, String month);
}
