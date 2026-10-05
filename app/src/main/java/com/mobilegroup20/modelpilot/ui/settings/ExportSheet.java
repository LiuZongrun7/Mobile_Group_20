package com.mobilegroup20.modelpilot.ui.settings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.mobilegroup20.modelpilot.BuildConfig;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.chat.CallLedger;
import com.mobilegroup20.modelpilot.data.Currency;
import com.mobilegroup20.modelpilot.data.RepositoryProvider;
import com.mobilegroup20.modelpilot.data.export.DataExporter;
import com.mobilegroup20.modelpilot.data.export.ExportArtifact;
import com.mobilegroup20.modelpilot.data.export.ExportFormat;
import com.mobilegroup20.modelpilot.data.export.ExportRequest;
import com.mobilegroup20.modelpilot.data.export.ExportResult;
import com.mobilegroup20.modelpilot.data.export.ExportScope;
import com.mobilegroup20.modelpilot.data.export.RoomExportSource;
import com.mobilegroup20.modelpilot.databinding.SheetExportBinding;
import com.mobilegroup20.modelpilot.util.TimeUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 「导出数据」（设计稿第 28 张，入口在「我的」页的 Data &amp; memory）。
 *
 * <p>这一层只做三件事：<b>问范围</b>、<b>把 {@link DataExporter} 的结果落到文件</b>、
 * <b>交给系统的保存界面</b>。所有「导出里该有什么」的规矩都在 {@code data/export} 里
 * （那边有单测钉着），这里一行判断都不该有。
 *
 * <p><b>它是弹层而不是对话框</b>（2026-10-06 真机上改的）：这一屏的内容比 AlertDialog
 * 能给的高度高，真机上量出来"格式选择"和"不含 key"那段被挤到屏幕外，
 * 而它们一个是要用户做的决定、一个是这一屏最该被读到的话。弹层能占满屏幕、
 * 标题与按钮固定、中间滚动（和「记忆」「选模型」两个弹层同一套做法）。
 *
 * <p><b>为什么先写缓存再让用户选位置。</b>系统那个「保存到…」界面会把本活动切到后台，
 * 转屏或被系统回收都会让弹层重建；字节只存在内存里的话，用户选完位置回来就什么都
 * 写不出来（表现是"选了位置，文件是空的"）。所以每次生成都先落到
 * {@code cacheDir/exports/}，选完位置只是把它<b>复制</b>过去。
 *
 * <p><b>缓存目录在每次生成前清空。</b>导出文件里有用户的聊天记录，留在缓存里越堆越多
 * 不是小事；而且文件名带时间戳，不清理的话每导一次就多一份。
 *
 * <p><b>所有 Android 对象都在主线程上取好再交给后台线程。</b>后台那段只拿
 * {@code Application} context 与 {@code File}——{@code requireContext()} /
 * {@code getString()} 一旦在后台线程上抛异常（Fragment 已 detach），表现是
 * "导出按钮点了没反应"，而异常在后台线程里，除了日志没人看得见。
 */
public final class ExportSheet extends BottomSheetDialogFragment {

    private static final String TAG = "export";

    private static final String STATE_PENDING = "pending_file";
    private static final String STATE_SUMMARY = "pending_summary";

    /** 导出文件在缓存里的落脚处。写入与清理都只认这一个目录。 */
    private static final String CACHE_DIR = "exports";

    private SheetExportBinding binding;

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /** 已经生成、等着被"复制到用户选的位置"的那个缓存文件（相对 {@link #CACHE_DIR}）。 */
    @Nullable
    private String pendingFile;

    /** 生成时报的条数。保存成功后原样再说一遍——两个时刻说的是同一份数据。 */
    @Nullable
    private String pendingSummary;

    public static void show(FragmentManager fm) {
        if (fm.findFragmentByTag(TAG) == null) {
            new ExportSheet().show(fm, TAG);
        }
    }

    /**
     * 系统的「保存到…」（SAF，**不申请存储权限**：用户选哪个我们才写得进哪个）。
     *
     * <p>用 {@code StartActivityForResult} 而不是 {@code CreateDocument} 契约：
     * 后者的 MIME 类型要在注册时定死，而我们的类型取决于用户选了 JSON 还是 CSV
     * （注册发生在字段初始化那一刻，那时候用户还没选）。
     */
    private final ActivityResultLauncher<Intent> saver = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                Intent data = result.getData();
                Uri target = data == null ? null : data.getData();
                if (result.getResultCode() != Activity.RESULT_OK || target == null) {
                    toast(getString(R.string.export_cancelled));
                    return;
                }
                copyTo(target);
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        binding = SheetExportBinding.inflate(inflater, parent, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle saved) {
        super.onViewCreated(view, saved);
        // **一打开就占满**（真机上量出来的）：弹层默认停在半高，而这个表单比半屏高，
        // 于是「Export」按钮落在屏幕外——要用户先把弹层往上拖才看得见主按钮，
        // 那是这一屏最不该有的一个动作。展开之后中间滚动、按钮固定在底下。
        expandFully();
        if (saved != null) {
            // 转屏恢复的是"已经生成好的那一份"，而不是重新导一遍（重导会换文件名，
            // 而用户此刻可能已经在新实例上点过"导出"了）。
            pendingFile = saved.getString(STATE_PENDING);
            pendingSummary = saved.getString(STATE_SUMMARY);
        }
        binding.exportScopeUsage.setOnCheckedChangeListener(
                (button, checked) -> setRangeEnabled(checked));
        setRangeEnabled(binding.exportScopeUsage.isChecked());
        binding.exportFormatGroup.setOnCheckedChangeListener(
                (group, checkedId) -> renderFormatNote());
        renderFormatNote();
        binding.exportCancel.setOnClickListener(v -> dismiss());
        binding.exportGo.setOnClickListener(v -> startExport());
        if (pendingFile != null) {
            setStatus(pendingSummary == null ? getString(R.string.export_cancelled) : pendingSummary);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_PENDING, pendingFile);
        out.putString(STATE_SUMMARY, pendingSummary);
    }

    /**
     * 让弹层直接展开到最大高度。
     *
     * <p>两处都要设：
     * <ul>
     *   <li>{@code setSkipCollapsed(true)}——不设的话即使叫它展开，用户往下拖一下就会
     *       停在半高，而半高状态下主按钮是看不见的；</li>
     *   <li>{@code setState(STATE_EXPANDED)}——真正把这一屏撑开。</li>
     * </ul>
     * 找不到弹层内部那个容器时静默跳过：那是 Material 的内部 id，真改了版本也不该
     * 让导出功能崩掉（最坏的结果只是要用户自己往上拖一下）。
     */
    private void expandFully() {
        if (!(getDialog() instanceof com.google.android.material.bottomsheet.BottomSheetDialog)) {
            return;
        }
        View sheet = getDialog().findViewById(
                com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) {
            return;
        }
        com.google.android.material.bottomsheet.BottomSheetBehavior<View> behavior =
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet);
        behavior.setSkipCollapsed(true);
        behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    @Override
    public void onDestroy() {
        // 优雅收尾而不是 shutdownNow：正在写文件的那一下让它写完，
        // 半截文件比没有文件更糟（用户会以为保存成功了）。
        io.shutdown();
        super.onDestroy();
    }

    // ==================== 生成 ====================

    private void startExport() {
        EnumSet<ExportScope> scopes = EnumSet.noneOf(ExportScope.class);
        if (binding.exportScopeConversations.isChecked()) {
            scopes.add(ExportScope.CONVERSATIONS);
        }
        if (binding.exportScopeUsage.isChecked()) {
            scopes.add(ExportScope.USAGE);
        }
        if (scopes.isEmpty()) {
            // 空范围导出的文件没有任何意义（DataExporter 也会拒），在这儿就说清楚。
            toast(getString(R.string.export_need_scope));
            return;
        }
        ExportRequest request = new ExportRequest(scopes, chosenFormat(),
                fromDay(scopes), toDay(scopes), System.currentTimeMillis());

        // 读取都在主线程做完：币种、汇率、版本号、数据库入口。
        final Context app = requireContext().getApplicationContext();
        final DataExporter exporter = exporter();

        setBusy(true);
        setStatus(getString(R.string.export_working));
        submit(() -> {
            try {
                ExportResult result = exporter.export(request);
                File file = writeToCache(app, result.artifact());
                onMain(() -> {
                    if (binding == null) {
                        return;                 // 弹层已经关了，别再动界面
                    }
                    pendingFile = file.getName();
                    pendingSummary = summary(result, scopes);
                    setBusy(false);
                    setStatus(pendingSummary);
                    saver.launch(saveIntent(result.artifact()));
                });
            } catch (Throwable failed) {
                String message = describe(failed);
                onMain(() -> {
                    setBusy(false);
                    setStatus(getString(R.string.export_failed, message));
                });
            }
        });
    }

    private DataExporter exporter() {
        // uid 固定 local：本机发出去的调用都挂这个号（见 CallLedger 的类注释）。
        // 币种与汇率也读本机设置——**导出里的人民币金额必须和界面上看到的是同一个**，
        // 所以汇率只有这一个来源，导出不另取一个。
        return new DataExporter(
                new RoomExportSource(RepositoryProvider.chats(), RepositoryProvider.usageCalls(),
                        CallLedger.LOCAL_UID),
                CallLedger.LOCAL_UID,
                BuildConfig.VERSION_NAME,
                Currency.code(requireContext()),
                Currency.rate(requireContext()));
    }

    private ExportFormat chosenFormat() {
        return binding.exportFormatCsv.isChecked() ? ExportFormat.CSV : ExportFormat.JSON;
    }

    /** 不限就返回 null：{@link ExportRequest} 用 null 表示那一头不设限。 */
    @Nullable
    private String fromDay(EnumSet<ExportScope> scopes) {
        if (!scopes.contains(ExportScope.USAGE)) {
            return null;
        }
        String today = TimeUtils.today();
        if (binding.exportRange7.isChecked()) {
            // "最近 7 天"含今天，所以往回数 6 天——数 7 天会变成"前 7 天"，
            // 而用户问的是"最近"。
            return TimeUtils.plusDays(today, -6);
        }
        if (binding.exportRange30.isChecked()) {
            return TimeUtils.plusDays(today, -29);
        }
        if (binding.exportRangeMonth.isChecked()) {
            return TimeUtils.firstDayOfMonth(TimeUtils.currentMonth());
        }
        return null;
    }

    @Nullable
    private String toDay(EnumSet<ExportScope> scopes) {
        if (!scopes.contains(ExportScope.USAGE) || binding.exportRangeAll.isChecked()) {
            return null;
        }
        return TimeUtils.today();
    }

    // ==================== 落盘 ====================

    /** 先写进缓存目录（理由见类注释）。**写之前清空**：上一次导出的内容不该留在手机里。 */
    private static File writeToCache(Context app, ExportArtifact artifact) throws Exception {
        File dir = new File(app.getCacheDir(), CACHE_DIR);
        deleteContents(dir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir);
        }
        File file = new File(dir, artifact.fileName);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(artifact.bytes);
        }
        return file;
    }

    private static void deleteContents(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            // 删不掉就算了：这是缓存，系统早晚会清；不该因为一个残留文件让导出失败。
            file.delete();
        }
    }

    /** 把缓存里那一份复制到用户选的位置。 */
    private void copyTo(Uri target) {
        final String name = pendingFile;
        final String summary = pendingSummary;
        if (name == null) {
            toast(getString(R.string.export_cancelled));
            return;
        }
        final Context app = requireContext().getApplicationContext();
        final File source = new File(new File(app.getCacheDir(), CACHE_DIR), name);

        setBusy(true);
        setStatus(getString(R.string.export_working));
        submit(() -> {
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = app.getContentResolver().openOutputStream(target)) {
                if (out == null) {
                    throw new IllegalStateException("no stream for " + target);
                }
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                out.flush();
                onMain(() -> {
                    dismissAllowingStateLoss();
                    toast(summary == null
                            ? getString(R.string.export_saved, name)
                            : getString(R.string.export_saved, name) + " · " + summary);
                });
            } catch (Throwable failed) {
                String message = describe(failed);
                onMain(() -> {
                    setBusy(false);
                    setStatus(getString(R.string.export_failed, message));
                });
            }
        });
    }

    private static Intent saveIntent(ExportArtifact artifact) {
        return new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(artifact.mimeType())
                .putExtra(Intent.EXTRA_TITLE, artifact.fileName);
    }

    // ==================== 界面小动作 ====================

    /** 后台任务统一从这里提交：弹层已经销毁时 executor 已 shutdown，直接丢掉这次请求。 */
    private void submit(Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException closed) {
            // 已经关了弹层：这次点击不该发生，也没有界面可以报错。
        }
    }

    private void setBusy(boolean busy) {
        if (binding == null) {
            return;
        }
        // 生成期间禁掉按钮：连点两次会生成两份、弹两次保存界面。
        binding.exportGo.setEnabled(!busy);
        binding.exportCancel.setEnabled(!busy);
    }

    private void setStatus(@Nullable String status) {
        if (binding == null) {
            return;
        }
        binding.exportStatus.setVisibility(status == null ? View.GONE : View.VISIBLE);
        if (status != null) {
            binding.exportStatus.setText(status);
        }
    }

    private void setRangeEnabled(boolean enabled) {
        if (binding == null) {
            return;
        }
        binding.exportRangeGroup.setEnabled(enabled);
        for (int i = 0; i < binding.exportRangeGroup.getChildCount(); i++) {
            View child = binding.exportRangeGroup.getChildAt(i);
            child.setEnabled(enabled);
            child.setAlpha(enabled ? 1f : 0.5f);
        }
    }

    private void renderFormatNote() {
        if (binding == null) {
            return;
        }
        binding.exportFormatNote.setText(binding.exportFormatCsv.isChecked()
                ? R.string.export_format_csv_note : R.string.export_format_json_note);
    }

    /** 「3 conversations · 340 messages · 88 calls」。某个范围一条都没有时也如实说。 */
    private String summary(ExportResult result, EnumSet<ExportScope> scopes) {
        List<String> parts = new ArrayList<>();
        if (scopes.contains(ExportScope.CONVERSATIONS)) {
            parts.add(getString(R.string.export_counts_conversations, result.conversations));
            parts.add(getString(R.string.export_counts_messages, result.messages));
            if (result.memories > 0) {
                parts.add(getString(R.string.export_counts_memories, result.memories));
            }
        }
        if (scopes.contains(ExportScope.USAGE)) {
            parts.add(getString(R.string.export_counts_usage, result.usageCalls));
        }
        if (parts.isEmpty()) {
            return getString(R.string.export_counts_empty);
        }
        return android.text.TextUtils.join(" · ", parts);
    }

    private static String describe(Throwable failed) {
        return failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage();
    }

    private void onMain(Runnable action) {
        if (isAdded()) {
            requireActivity().runOnUiThread(action);
        }
    }

    private void toast(String message) {
        if (isAdded()) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
        }
    }
}
