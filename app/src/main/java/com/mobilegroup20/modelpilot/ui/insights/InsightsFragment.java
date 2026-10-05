package com.mobilegroup20.modelpilot.ui.insights;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.chat.CallLedger;
import com.mobilegroup20.modelpilot.data.Budget;
import com.mobilegroup20.modelpilot.data.Currency;
import com.mobilegroup20.modelpilot.data.FxRate;
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
 *
 * <p><b>（2026-10-05）这一页多了一个币种选择。</b>账本里永远是微美元，
 * 币种只决定怎么显示：美元是原值，人民币要按<b>用户自己填的汇率</b>折
 * （见 {@link Currency}）。没填汇率就<b>不折</b>，退回美元并在卡片里说明——
 * 编一个汇率出来会让这一页每个数字都变成假的，而且看不出来。
 * 所有金额都从这里出：{@link #money(long)}，别在渲染里自己乘。
 */
public final class InsightsFragment extends Fragment {

    /**
     * 汇率对话框里往上列几条历史。列多了对话框比屏幕还长，
     * 而这个问题（"上个月那个数按哪版汇率算的"）一般只关心最近一两版。
     */
    private static final int HISTORY_SHOWN = 3;

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
        binding.insightsCurrencyChange.setOnClickListener(v -> pickCurrency());
        binding.insightsFxSet.setOnClickListener(v -> editRate());
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
        renderCurrency();
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

    /**
     * 金额显示的唯一出口（见类注释）。**界面上不许自己判币种、自己乘汇率**：
     * 两个页面各乘一遍，迟早会乘出两个不一样的值。
     */
    private String money(long usdMicros) {
        return Currency.format(requireContext(), usdMicros);
    }

    private void renderCost(Insights.Summary summary) {
        Context context = requireContext();
        boolean anyPriced = summary.calls > summary.unknownCostCalls;
        binding.insightsCostValue.setText(anyPriced
                ? money(summary.costMicros)
                : getString(R.string.insights_cost_unknown));
        String note = anyPriced
                ? getString(R.string.insights_cost_note, summary.unknownCostCalls, summary.calls)
                : getString(R.string.insights_cost_all_unknown, summary.calls);
        // 人民币是折出来的，那就在数字底下再说一遍按什么折的。
        // **只有真的折了才说**：金额还是"未知"的时候挂一句"按 7.15 折算"，
        // 等于把一句没有对象的话摆在最显眼的位置。
        String provenance = anyPriced ? Currency.provenance(context) : null;
        binding.insightsCostNote.setText(provenance == null
                ? note
                : note + "\n" + getString(R.string.insights_cost_converted, provenance));
    }

    /**
     * 币种卡片。三种状态各有各的说法，**不能合并**：
     * 美元（账本原值）、人民币且填了汇率（折的）、人民币但没汇率（没折，还是美元）。
     * 第三种如果不说出来，用户会以为自己看的是人民币。
     */
    private void renderCurrency() {
        Context context = requireContext();
        boolean cny = Currency.CNY.equals(Currency.code(context));
        FxRate rate = Currency.rate(context);

        binding.insightsCurrencyValue.setText(cny
                ? R.string.insights_currency_cny
                : R.string.insights_currency_usd);

        // 汇率那一行只在"真的按它折了"的时候出现；没折却显示一个汇率会让人以为折了。
        String provenance = cny && rate.isSet() ? Currency.provenance(context) : null;
        binding.insightsFxLine.setVisibility(provenance == null ? View.GONE : View.VISIBLE);
        if (provenance != null) {
            binding.insightsFxLine.setText(provenance);
        }

        binding.insightsFxSet.setVisibility(cny ? View.VISIBLE : View.GONE);
        binding.insightsFxSet.setText(rate.isSet()
                ? R.string.insights_fx_change
                : R.string.insights_fx_set);

        if (!cny) {
            binding.insightsCurrencyNote.setText(R.string.insights_currency_note_usd);
        } else if (rate.isSet()) {
            binding.insightsCurrencyNote.setText(R.string.insights_currency_note_cny);
        } else {
            binding.insightsCurrencyNote.setText(R.string.insights_currency_note_no_rate);
        }
    }

    /**
     * 月度上限那一段。
     *
     * <p><b>已花的那个数不许无条件写成一个确定值</b>：账本里只要有算不出价的调用，
     * 已知金额就只是<b>下界</b>。上面那张卡片为这件事专门显示"Unknown / 其中 N 次没有价格"，
     * 这里却写一个"¥0.00 of ¥143.00"的话，同一页上就有两个口径，
     * 而且更小的那个（0）看起来才是"真实花费"。所以三种情况分开写：
     * 一次都没价 → Unknown；有价也有未知 → at least X；全有价 → X。
     */
    private void renderBudget(Insights.Summary summary) {
        long limitMicros = Budget.limitMicros(requireContext());
        if (limitMicros <= 0) {
            binding.insightsBudgetValue.setText(R.string.insights_budget_none);
            binding.insightsBudgetBar.setProgress(0);
            binding.insightsBudgetNote.setText(R.string.insights_budget_why);
            return;
        }
        String spent;
        if (summary.calls > 0 && summary.unknownCostCalls == summary.calls) {
            spent = getString(R.string.insights_cost_unknown);
        } else if (summary.unknownCostCalls > 0) {
            spent = getString(R.string.insights_budget_at_least, money(summary.costMicros));
        } else {
            spent = money(summary.costMicros);
        }
        binding.insightsBudgetValue.setText(getString(R.string.insights_budget_value,
                spent, money(limitMicros)));
        int percent = (int) Math.min(100, Math.round(summary.costMicros * 100.0 / limitMicros));
        binding.insightsBudgetBar.setProgress(percent);
        if (summary.unknownCostCalls > 0) {
            // 还剩多少也只能说"最多"：那几笔没价的迟早要让这个数变小。
            binding.insightsBudgetNote.setText(getString(R.string.insights_budget_left_at_most,
                    money(Math.max(0L, limitMicros - summary.costMicros)),
                    summary.unknownCostCalls));
            return;
        }
        boolean over = summary.costMicros > limitMicros;
        binding.insightsBudgetNote.setText(over
                ? getString(R.string.insights_budget_over)
                : getString(R.string.insights_budget_under,
                        money(limitMicros - summary.costMicros)));
    }

    private void renderBuckets(LinearLayout container, List<Insights.Bucket> buckets) {
        container.removeAllViews();
        for (Insights.Bucket bucket : buckets) {
            ItemInsightsBucketBinding row = ItemInsightsBucketBinding.inflate(
                    getLayoutInflater(), container, false);
            row.bucketLabel.setText(bucket.label);
            String cost = bucket.costKnown()
                    ? money(bucket.costMicros)
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

    // ==================== 币种与汇率 ====================

    /**
     * 选显示币种。**选人民币但没有汇率时，紧接着把汇率问出来**——
     * 不然用户会停在"选了人民币、看到的还是美元"这个状态里，
     * 那看起来像 bug，而不像"还差一步"。
     */
    private void pickCurrency() {
        Context context = requireContext();
        String[] labels = {getString(R.string.insights_currency_usd),
                getString(R.string.insights_currency_cny)};
        boolean cny = Currency.CNY.equals(Currency.code(context));
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.insights_currency_pick)
                .setSingleChoiceItems(labels, cny ? 1 : 0, (dialog, which) -> {
                    dialog.dismiss();
                    Currency.setCode(context, which == 1 ? Currency.CNY : Currency.USD);
                    renderCurrency();
                    // 上面的金额是按旧币种格式化的，**必须整页重画**——
                    // 只刷这张卡片的话，会留下一页"卡片写着美元、金额还是人民币"。
                    load();
                    if (Currency.needsRate(context)) {
                        editRate();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 填 / 改汇率，以及**把来源一起记下来**（留痕；历史见 {@link #renderRateHistory}）。
     *
     * <p>两个拦一道的地方，都是为了不让一个手滑的数字静静地改掉整页金额：
     * 填反了（{@link FxRate#looksInverted}）和量级离谱（{@link FxRate#plausible}）。
     * 它们都只是<b>多问一句</b>，不替用户改数——汇率是他填的，决定权在他。
     */
    private void editRate() {
        Context context = requireContext();
        View view = getLayoutInflater().inflate(R.layout.dialog_fx_rate, null);
        EditText rateInput = view.findViewById(R.id.fx_rate_input);
        EditText sourceInput = view.findViewById(R.id.fx_source_input);
        TextView history = view.findViewById(R.id.fx_history);

        FxRate current = Currency.rate(context);
        if (current.isSet()) {
            rateInput.setText(current.label());
            rateInput.setSelection(rateInput.getText().length());
        }
        if (current.hasSource()) {
            sourceInput.setText(current.source);
        }
        renderRateHistory(history);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.insights_fx_dialog_title)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.insights_fx_clear, null)
                .setPositiveButton(R.string.keys_save, null)
                .create();
        dialog.show();

        // 保存要能"拦住"：校验不过就不许关。所以按钮的监听是 show() 之后自己挂的，
        // 而不是 setPositiveButton 里那个（那个无论如何都会把对话框关掉）。
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            long micros = FxRate.parse(rateInput.getText().toString());
            String source = sourceInput.getText().toString().trim();
            if (micros <= 0L) {
                rateInput.setError(getString(R.string.insights_fx_bad));
                return;
            }
            if (FxRate.looksInverted(micros)) {
                confirmInverted(micros, source, dialog);
                return;
            }
            if (!FxRate.plausible(micros)) {
                confirmUnusual(micros, source, dialog);
                return;
            }
            saveRate(micros, source, dialog);
        });

        // 没填过汇率时不给"清空"：那颗按钮在那时候没有任何意义。
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(
                current.isSet() ? View.VISIBLE : View.GONE);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            Currency.setRate(context, 0L, null);
            dialog.dismiss();
            renderCurrency();
            load();                       // 金额显示方式变了，整页重画
        });
    }

    /** 「你是不是把方向填反了」——把倒过来的那个值算给他看，只提议、不代填。 */
    private void confirmInverted(long micros, String source, AlertDialog parent) {
        long inverted = FxRate.inverted(micros);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.insights_fx_inverted_title)
                .setMessage(getString(R.string.insights_fx_inverted,
                        FxRate.format(micros), FxRate.format(inverted)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok,
                        (d, w) -> saveRate(inverted, source, parent))
                .show();
    }

    /** 量级离谱（比如多打了一位数）：确认就存，不确认就退回去改。 */
    private void confirmUnusual(long micros, String source, AlertDialog parent) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.insights_fx_unusual_title)
                .setMessage(getString(R.string.insights_fx_unusual, FxRate.format(micros)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok,
                        (d, w) -> saveRate(micros, source, parent))
                .show();
    }

    private void saveRate(long micros, String source, AlertDialog parent) {
        Currency.setRate(requireContext(), micros, source);
        parent.dismiss();
        renderCurrency();
        load();                           // 金额显示方式变了，整页重画
    }

    /** 「之前那几版汇率是多少」——留痕在界面上的样子。只有一条（就是当前这条）时不显示。 */
    private void renderRateHistory(TextView view) {
        List<FxRate> history = Currency.history(requireContext());
        if (history.size() <= 1) {
            view.setVisibility(View.GONE);
            return;
        }
        StringBuilder text = new StringBuilder(getString(R.string.insights_fx_history));
        for (int i = 1; i < history.size() && i <= HISTORY_SHOWN; i++) {
            FxRate entry = history.get(i);
            String tail = entry.hasSource() ? entry.enteredOn() + " · " + entry.source
                    : entry.enteredOn();
            text.append('\n').append(getString(R.string.insights_fx_history_row,
                    entry.label(), tail));
        }
        view.setText(text);
        view.setVisibility(View.VISIBLE);
    }

    /**
     * 设本地的月度上限（**不是充值，也不是套餐**：只是一个自己填的数字，
     * 到了就在这一页提醒自己）。**按用户选的币种输入**，存的时候折成微美元。
     *
     * <p>两个容易写错的地方：
     * <ul>
     *   <li><b>预填的数必须和这个框的币种一致。</b>上限存的是微美元，
     *       框上写着 CNY 却把美元数填进去，用户一按保存就把 $20 存成了 ¥20——少 7 倍，
     *       而且界面会立刻显示成 ¥143，看起来像"我什么时候填过 143"。</li>
     *   <li><b>选了人民币却没填汇率时不存。</b>折不出来就是折不出来，
     *       硬存等于替用户编了一个汇率（见 {@link Currency#enteredToUsdMicros}）。</li>
     * </ul>
     */
    private void editBudget() {
        Context context = requireContext();
        boolean cny = Currency.CNY.equals(Currency.code(context));       // 用户选的
        boolean convertible = Currency.CNY.equals(Currency.shownCode(context));  // 真的能折的
        FxRate rate = Currency.rate(context);

        EditText input = new EditText(context);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint(cny ? getString(R.string.insights_currency_cny)
                : getString(R.string.insights_currency_usd));

        long limitMicros = Budget.limitMicros(context);
        if (limitMicros > 0 && cny) {
            // 存的是美元：能折就折成人民币预填，不能折就空着（填美元数会被当成人民币）。
            limitMicros = convertible
                    ? Money.toCnyMicros(limitMicros, rate.cnyPerUsdMicros)
                    : 0L;
        }
        if (limitMicros > 0) {
            input.setText(String.format(Locale.US, "%.2f", limitMicros / 1_000_000.0));
        }

        int help = !cny ? R.string.insights_budget_help_usd
                : convertible ? R.string.insights_budget_help_cny
                : R.string.insights_budget_help_cny_no_rate;
        new MaterialAlertDialogBuilder(context)
                .setTitle(cny ? R.string.insights_budget_title_cny
                        : R.string.insights_budget_title_usd)
                .setMessage(help)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.keys_save, (dialog, which) -> {
                    Long usdMicros = Currency.enteredToUsdMicros(context,
                            input.getText().toString());
                    if (usdMicros == null) {
                        new MaterialAlertDialogBuilder(context)
                                .setTitle(R.string.insights_fx_dialog_title)
                                .setMessage(R.string.insights_budget_need_rate)
                                .setPositiveButton(R.string.insights_fx_set,
                                        (d, w) -> editRate())
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                        return;
                    }
                    Budget.setLimitMicros(context, usdMicros);
                    load();
                })
                .show();
    }

    private String formatTokens(long tokens) {
        return String.format(Locale.US, "%,d", tokens);
    }
}
