package com.mobilegroup20.modelpilot.data.stub;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.mobilegroup20.modelpilot.contract.model.Budget;
import com.mobilegroup20.modelpilot.contract.tool.BudgetStatus;
import com.mobilegroup20.modelpilot.contract.tool.Coverage;
import com.mobilegroup20.modelpilot.data.repository.BudgetRepository;
import com.mobilegroup20.modelpilot.util.TimeUtils;

/**
 * {@link BudgetRepository} 的桩实现：一个固定上限的假预算。
 *
 * <p>作用是让「预算警告」这条链路（预算卡片、越线提示、agent 的
 * {@code getBudgetStatus}）在真实数据接好之前就能跑通。数值是写死的，
 * 接上真实现后这个类可以删。
 */
public class StubBudgetRepository implements BudgetRepository {

    /** 编的月度上限：20 美元。 */
    private static final long CAP_MICROS = 20_000_000L;

    /** 编的已花金额：13 美元，刚好压在 80% 警告线之上，方便看越线效果。 */
    private static final long SPENT_MICROS = 13_000_000L;

    @Override
    public LiveData<Budget> budgetOf(String uid, String month) {
        return live(new Budget(uid, month, CAP_MICROS, 0.8));
    }

    @Override
    public LiveData<Budget> saveBudget(Budget budget) {
        // 桩不落库，原样返回，让调用方至少能拿到一个非空结果。
        return live(budget);
    }

    @Override
    public LiveData<BudgetStatus> status(String uid, String month) {
        BudgetStatus s = new BudgetStatus();
        s.month = month;
        s.capMicros = CAP_MICROS;
        s.spentMicros = SPENT_MICROS;
        s.warnAtRatio = 0.8;
        s.configured = true;

        Coverage coverage = new Coverage();
        coverage.from = TimeUtils.firstDayOfMonth(month);
        coverage.to = TimeUtils.yesterday();
        coverage.daysWithData = TimeUtils.daysBetween(coverage.from, coverage.to).size();
        s.coverage = coverage;

        return live(s);
    }

    private static <T> LiveData<T> live(T value) {
        MutableLiveData<T> data = new MutableLiveData<>();
        data.postValue(value);
        return data;
    }
}
