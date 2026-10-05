package com.mobilegroup20.modelpilot.ui.insights;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.chat.CallLedger;
import com.mobilegroup20.modelpilot.data.Budget;
import com.mobilegroup20.modelpilot.data.Money;
import com.mobilegroup20.modelpilot.data.RepositoryProvider;
import com.mobilegroup20.modelpilot.data.local.UsageCallEntity;
import com.mobilegroup20.modelpilot.databinding.FragmentInsightsBinding;
import com.mobilegroup20.modelpilot.databinding.ItemInsightsBucketBinding;
import com.mobilegroup20.modelpilot.util.TimeUtils;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Insights（大纲 §5 的第 4 屏）：**读本机账本**，不是读服务端、也不是读桩数据。
 *
 * <p>它和旧统计页的根本区别：旧页读 `daily_usage`（只滚导入记录）而且开着桩数据，
 * 所以它显示的数字和"这个 App 真实花了多少"毫无关系——演示时最容易被问到
 * "这个 946,668 是哪来的"。这一页的每一个数字都能追回 `usage_call` 里的原始行。
 *
 * <p>两个刻意的显示规则：
 * <ol>
 *   <li><b>算不出价的调用单独计数并显示</b>（"其中 N 次还没有价格"）。
 *       只显示一个金额的话，用户会以为"总共就花了这么多"，
 *       而真相是"有一部分我们不知道"（`CONTRACTS.md` §4）。</li>
 *   <li><b>预算超了要变色并说明</b>，而不是只把进度条拉满——
 *       拉满的进度条和 100% 用尽看起来一样，但用户要做的事不同。</li>
 * </ol>
 */
public final class InsightsFragment extends Fragment {

    private FragmentInsightsBinding binding;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        binding = FragmentInsightsBinding.inflate(inflater, parent, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle saved) {
        binding.insightsBudgetSet.setOnClickListener(v -> editBudget());
        load();
    }

    @Override
    public void onResume() {
        super.onResume();
        load();                       // 从对话页回来时可能有新账，重新滚一次
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    /** 查库在后台线程（Room 的同步查询不许在主线程）。 */
    private void load() {
        if (binding == null) {
            return;
        }
        String today = TimeUtils.today();
        String from = today.substring(0, 8) + "01";
        String to = today.substring(0, 8) + "31";
        io.execute(() -> {
            List<UsageCallEntity> calls = RepositoryProvider.usageCalls()
                    .localCallsInRange(CallLedger.LOCAL_UID, from, to);
            Insights.Summary summary = Insights.summarize(calls);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> render(summary));
            }
        });
    }

    private void render(Insights.Summary summary) {
        if (binding == null) {
            return;
        }
        binding.insightsMonth.setText(getString(R.string.insights_month_line,
                TimeUtils.currentMonthLabel(), getString(R.string.insights_this_device)));
        binding.insightsTokensValue.setText(formatTokens(summary.tokens));
        binding.insightsTokensNote.setText(getString(R.string.insights_tokens_note,
                formatTokens(summary.input), formatTokens(summary.cacheRead + summary.cacheWrite),
                formatTokens(summary.output)));
        renderCost(summary);
        renderBudget(summary);
        binding.insightsRoutingValue.setText(summary.answerCalls == 0
                ? getString(R.string.insights_routing_none)
                : getString(R.string.insights_routing_value,
                        summary.autoAnswerCalls, summary.answerCalls,
                        summary.autoPercent() == null ? 0 : summary.autoPercent(),
                        summary.compressCalls));
        renderBuckets(binding.insightsProvidersContainer, summary.providers);
        renderBuckets(binding.insightsModelsContainer, summary.models);
        binding.insightsEmpty.setVisibility(summary.calls == 0 ? View.VISIBLE : View.GONE);
    }

    private void renderCost(Insights.Summary summary) {
        boolean anyPriced = summary.calls > summary.unknownCostCalls;
        binding.insightsCostValue.setText(anyPriced
                ? Money.formatUsd(summary.costMicros)
                : getString(R.string.insights_cost_unknown));
        binding.insightsCostNote.setText(anyPriced
                ? getString(R.string.insights_cost_note, summary.unknownCostCalls, summary.calls)
                : getString(R.string.insights_cost_all_unknown, summary.calls));
    }

    private void renderBudget(Insights.Summary summary) {
        long limitMicros = Budget.limitMicros(requireContext());
        if (limitMicros <= 0) {
            binding.insightsBudgetValue.setText(R.string.insights_budget_none);
            binding.insightsBudgetBar.setProgress(0);
            binding.insightsBudgetNote.setText(R.string.insights_budget_why);
            return;
        }
        binding.insightsBudgetValue.setText(getString(R.string.insights_budget_value,
                Money.formatUsd(summary.costMicros), Money.formatUsd(limitMicros)));
        int percent = (int) Math.min(100, Math.round(summary.costMicros * 100.0 / limitMicros));
        binding.insightsBudgetBar.setProgress(percent);
        boolean over = summary.costMicros > limitMicros;
        binding.insightsBudgetNote.setText(over
                ? getString(R.string.insights_budget_over)
                : getString(R.string.insights_budget_under,
                        Money.formatUsd(limitMicros - summary.costMicros)));
    }

    private void renderBuckets(LinearLayout container, List<Insights.Bucket> buckets) {
        container.removeAllViews();
        for (Insights.Bucket bucket : buckets) {
            ItemInsightsBucketBinding row = ItemInsightsBucketBinding.inflate(
                    getLayoutInflater(), container, false);
            row.bucketLabel.setText(bucket.label);
            String cost = bucket.costKnown()
                    ? Money.formatUsd(bucket.costMicros)
                    : getString(R.string.insights_cost_unknown);
            String detail = getString(R.string.insights_bucket_detail,
                    bucket.calls, formatTokens(bucket.tokens), cost);
            if (bucket.unknownCostCalls > 0 && bucket.costKnown()) {
                // 有价格也有未知：说清楚"这个金额只覆盖其中几次"。
                detail = detail + " · " + getString(R.string.insights_bucket_partial,
                        bucket.unknownCostCalls);
            }
            row.bucketDetail.setText(detail);
            container.addView(row.getRoot());
        }
    }

    /**
     * 设本地的月度上限（**不是充值，也不是套餐**：只是一个自己填的数字，
     * 到了就在这一页提醒自己）。填 0 或留空 = 不设。
     */
    private void editBudget() {
        EditText input = new EditText(requireContext());
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        long current = Budget.limitMicros(requireContext());
        if (current > 0) {
            input.setText(String.format(Locale.US, "%.2f", current / 1_000_000.0));
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.insights_budget_set)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.keys_save, (dialog, which) -> {
                    Budget.setLimitUsd(requireContext(), input.getText().toString());
                    load();
                })
                .show();
    }

    private String formatTokens(long tokens) {
        return String.format(Locale.US, "%,d", tokens);
    }
}
