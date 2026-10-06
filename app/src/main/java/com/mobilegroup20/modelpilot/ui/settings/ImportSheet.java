package com.mobilegroup20.modelpilot.ui.settings;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
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
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.data.importer.DataImporter;
import com.mobilegroup20.modelpilot.data.importer.ExportFileReader;
import com.mobilegroup20.modelpilot.data.importer.ImportBundle;
import com.mobilegroup20.modelpilot.data.importer.ImportFileException;
import com.mobilegroup20.modelpilot.data.importer.ImportPreview;
import com.mobilegroup20.modelpilot.data.importer.ImportSummary;
import com.mobilegroup20.modelpilot.data.importer.ImportWording;
import com.mobilegroup20.modelpilot.data.importer.MergePolicy;
import com.mobilegroup20.modelpilot.data.importer.RoomImportTarget;
import com.mobilegroup20.modelpilot.databinding.SheetImportBinding;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 「导入数据」（`Me → Data & memory` 的第二颗按钮，和 {@link ExportSheet} 是一对）。
 *
 * <p>导出解决「能带走」，这一屏解决<b>「换回来」</b>——没有它，换台手机之后用户手上
 * 有个文件而 App 里空空如也。
 *
 * <h3>这一层只做四件事</h3>
 * <ol>
 *   <li><b>让用户选文件</b>（SAF，不申请存储权限：他选哪个我们才读得到哪个）；</li>
 *   <li><b>把字节读进来</b>（限大小，见 {@link #MAX_BYTES}）；</li>
 *   <li><b>把"会变成什么"摆出来</b>（{@link ImportPreview}）并让他选策略；</li>
 *   <li><b>写</b>（{@link DataImporter#apply}）并报结果。</li>
 * </ol>
 * 「什么算坏文件」「哪些对话该覆盖」「缺字段怎么办」全在 {@code data/importer} 里
 * （那边有单测钉着），这里一行判断都不该有。
 *
 * <h3>两条真机上学到的规矩</h3>
 * <ul>
 *   <li><b>所有 Android 对象都在主线程上取好再交给后台线程。</b>后台那段只拿
 *       {@code Application} context 与字节——{@code requireContext()} 一旦在后台线程上
 *       抛异常（Fragment 已 detach），表现是"点了导入没反应"，而异常在后台线程里，
 *       除了日志没人看得见。</li>
 *   <li><b>解析与写库分两步、各自报状态。</b>导一次要读一个文件再写几张表，
 *       中间可能停好几秒；只报一句"导入中"的话，用户会以为它卡住了。</li>
 * </ul>
 *
 * <p><b>它是弹层而不是对话框</b>：理由同 {@link ExportSheet}——这一屏的内容比
 * AlertDialog 能给的高度高，而"策略"那一组和"本机已有的怎么办"恰恰是被挤出去的那部分，
 * 那是本屏唯一需要用户做的决定。
 */
public final class ImportSheet extends BottomSheetDialogFragment {

    private static final String TAG = "import";

    private static final String STATE_SUMMARY = "summary";

    /**
     * 读进来的文件最多多大：32 MiB。
     *
     * <p>导出文件一般只有几十 KB（几百条对话也就几百 KB），这个上限不是给正常文件用的，
     * 是防"选错文件"——手机上有的是几百 MB 的视频，把它整个读进内存会直接把 App 撑死。
     * 超了就说清楚是多大、并且不读。
     */
    private static final long MAX_BYTES = 32L * 1024 * 1024;

    private SheetImportBinding binding;

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /** 读好、还没写的那一份。转屏之后靠它恢复，不用让用户再选一次文件。 */
    @Nullable
    private ImportBundle loaded;

    /** 选完文件之后生成的那份预览（只有 {@link #loaded} 非空时才有）。 */
    @Nullable
    private ImportPreview preview;

    /** 已经写完之后的结果，转屏时原样再显示一次。 */
    @Nullable
    private String doneSummary;

    public static void show(FragmentManager fm) {
        if (fm.findFragmentByTag(TAG) == null) {
            new ImportSheet().show(fm, TAG);
        }
    }

    /**
     * 系统文件选择器。
     *
     * <p>MIME 列表里<b>必须带通配的 {@code *} 斜杠 {@code *}</b>：导出的 CSV 产物是一个 zip，
     * 而不少设备上的文件管理器把它报成 {@code application/octet-stream}，只列 json/zip
     * 会让文件变灰、用户以为"文件在那儿却选不了"。真正的格式判定在读字节那一步
     * （{@code ExportFileReader} 只认内容），所以这里放宽没有风险。
     */
    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) {
                    return;     // 用户返回了，不是错
                }
                read(uri);
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        binding = SheetImportBinding.inflate(inflater, parent, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle saved) {
        super.onViewCreated(view, saved);
        // 一打开就占满：理由同导出那一屏（按钮与策略不能被挤到屏幕外）。
        expandFully();
        if (saved != null) {
            doneSummary = saved.getString(STATE_SUMMARY);
        }
        binding.importPick.setOnClickListener(v -> pick());
        binding.importPolicyGroup.setOnCheckedChangeListener(
                (group, checkedId) -> renderPolicyNote());
        renderPolicyNote();
        binding.importCancel.setOnClickListener(v -> dismiss());
        binding.importGo.setOnClickListener(v -> startImport());
        if (doneSummary != null) {
            setStatus(doneSummary);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_SUMMARY, doneSummary);
    }

    /** 让弹层直接展开到最大高度（同导出那一屏，两处都要设）。 */
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
        // 优雅收尾：正在写库的那一下让它写完。写一半丢下一次导入的后果是
        // "有些对话进来了、有些没进来"，而用户看不出是哪一半。
        io.shutdown();
        super.onDestroy();
    }

    // ==================== 选文件 → 读 → 预览 ====================

    private void pick() {
        picker.launch(new String[]{"application/json", "application/zip",
                "application/octet-stream", "text/csv", "*/*"});
    }

    /**
     * 把文件读进来并生成预览。
     *
     * <p>读之前先问大小（{@link OpenableColumns#SIZE}）：有些 provider 拿不到大小（返回 -1），
     * 那时候只能边读边数，数到上限就停——两种路都留着，因为"拿不到大小"在真机上很常见
     * （云盘的文件就是）。
     */
    private void read(Uri uri) {
        final Context app = requireContext().getApplicationContext();   // 主线程上取好
        final String name = displayName(app, uri);
        final String tooBig = getString(R.string.import_too_big, humanBytes(MAX_BYTES));

        setBusy(true);
        setStatus(getString(R.string.import_working));
        clearPreview();
        submit(() -> {
            try {
                byte[] bytes = readBytes(app, uri);
                ImportBundle bundle = ExportFileReader.read(bytes, name);
                ImportPreview result = new DataImporter(new RoomImportTarget(app)).preview(bundle);
                onMain(() -> {
                    if (binding == null) {
                        return;
                    }
                    loaded = bundle;
                    preview = result;
                    setBusy(false);
                    // **读完要把「Reading the file…」抹掉**（真机上抓到的）：那一行原本一直
                    // 挂在预览下面，看起来像还在读——而它下面就是用户要做的那个决定。
                    setStatus(null);
                    renderPreview(name, bytes.length);
                    renderPolicyNote();
                });
            } catch (ImportFileException refused) {
                String message = refused.problem;
                onMain(() -> {
                    setBusy(false);
                    setStatus(getString(R.string.import_failed, message));
                });
            } catch (TooBigException big) {
                onMain(() -> {
                    setBusy(false);
                    setStatus(tooBig);
                });
            } catch (Throwable failed) {
                String message = describe(failed);
                onMain(() -> {
                    setBusy(false);
                    setStatus(getString(R.string.import_failed, message));
                });
            }
        });
    }

    // ==================== 写 ====================

    private void startImport() {
        final ImportBundle bundle = loaded;
        if (bundle == null) {
            return;
        }
        final MergePolicy policy = chosenPolicy();
        final Context app = requireContext().getApplicationContext();

        setBusy(true);
        setStatus(getString(R.string.import_writing));
        submit(() -> {
            try {
                ImportSummary summary =
                        new DataImporter(new RoomImportTarget(app)).apply(bundle, policy);
                String text = ImportWording.resultLine(summary);
                onMain(() -> {
                    if (binding == null) {
                        return;
                    }
                    setBusy(false);
                    doneSummary = text;
                    setStatus(text);
                    toast(text);
                    // 导完就关：这一屏没有别的可做的，留着只会挡住「我的」页。
                    dismissAllowingStateLoss();
                });
            } catch (Throwable failed) {
                String message = describe(failed);
                onMain(() -> {
                    setBusy(false);
                    setStatus(getString(R.string.import_failed, message));
                });
            }
        });
    }

    /**
     * 结果那一行：{@link ImportSummary#describe()} 加上"有几行没读进来"。
     *
     * <p>两件事必须一起说：写进去多少、以及<b>没读进来多少</b>。只报前者的话，
     * 一份有 30 行读不动的文件看起来和一份完整的文件一模一样，
     * 而用户会以为数据齐了——这正是最该避免的那种错。
     */
    private MergePolicy chosenPolicy() {
        return binding != null && binding.importPolicyReplace.isChecked()
                ? MergePolicy.REPLACE : MergePolicy.ADD_ONLY;
    }

    // ==================== 预览怎么显示 ====================

    private void renderPreview(String name, int bytes) {
        if (binding == null || preview == null || loaded == null) {
            return;
        }
        ImportBundle bundle = loaded;
        binding.importSource.setText(
                ImportWording.sourceLine(name, humanBytes(bytes), bundle.format));

        // 自述：谁导的、什么时候导的。**没有就说没有**，不编一个版本号出来。
        // 句子在 ImportWording 里（那边有单测），这里只负责塞进哪个 TextView。
        binding.importFacts.setText(ImportWording.factsLine(bundle.facts));

        binding.importCounts.setText(
                android.text.TextUtils.join("\n", ImportWording.previewLines(preview)));

        // 文件自己写的那段取舍（"附件没有本机路径"之类）原样转述，见 ImportFacts 的注释。
        if (bundle.facts.notes.isEmpty()) {
            binding.importNotes.setVisibility(View.GONE);
        } else {
            binding.importNotes.setVisibility(View.VISIBLE);
            StringBuilder notes = new StringBuilder(getString(R.string.import_notes_title));
            for (String note : bundle.facts.notes) {
                notes.append('\n').append("· ").append(note);
            }
            binding.importNotes.setText(notes);
        }

        // 读不动的行：只列前几条 + 一个总数，不刷屏。
        List<String> problems = preview.firstProblems(3);
        if (problems.isEmpty()) {
            binding.importProblems.setVisibility(View.GONE);
        } else {
            binding.importProblems.setVisibility(View.VISIBLE);
            StringBuilder text = new StringBuilder(getString(R.string.import_problems_title));
            for (String problem : problems) {
                text.append('\n').append("· ").append(problem);
            }
            int rest = preview.problems().size() - problems.size();
            if (rest > 0) {
                text.append('\n').append(getString(R.string.import_problems_more, rest));
            }
            binding.importProblems.setText(text);
        }

        renderPolicyNote();
    }

    private void clearPreview() {
        if (binding == null) {
            return;
        }
        binding.importSource.setText("");
        binding.importFacts.setText("");
        binding.importCounts.setText("");
        binding.importNotes.setVisibility(View.GONE);
        binding.importProblems.setVisibility(View.GONE);
    }

    /** 策略说明随选择变；按钮的可点状态也跟着变（见 {@link #renderPolicyNote()}）。 */
    private void renderPolicyNote() {
        if (binding == null) {
            return;
        }
        boolean replace = binding.importPolicyReplace.isChecked();
        binding.importPolicyNote.setText(replace
                ? R.string.import_policy_replace_note : R.string.import_policy_add_note);
        binding.importGo.setEnabled(canImport(replace ? MergePolicy.REPLACE : MergePolicy.ADD_ONLY));
    }

    /**
     * 现在能不能导。
     *
     * <p>「只加新的」且一条新东西都没有时按钮是灰的：那个动作什么都不会做，
     * 让用户点一个空动作比拦住他更糟（他会以为导入失败）。换成「覆盖」之后又能点了，
     * 因为那确实有事可做。
     */
    private boolean canImport(MergePolicy policy) {
        if (loaded == null || preview == null) {
            return false;
        }
        return !(policy == MergePolicy.ADD_ONLY && preview.nothingToAdd());
    }

    // ==================== 读字节 ====================

    /** 超过上限时抛这个（不读完整份文件）。 */
    private static final class TooBigException extends Exception {
    }

    private static byte[] readBytes(Context app, Uri uri) throws Exception {
        ContentResolver resolver = app.getContentResolver();
        Long declared = declaredSize(app, uri);
        if (declared != null && declared > MAX_BYTES) {
            throw new TooBigException();
        }
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                throw new IllegalStateException("no stream for " + uri);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                if (buffer.size() + read > MAX_BYTES) {
                    // provider 没报大小，只能边读边拦。**先拦再写**：写进去再判断
                    // 等于已经把它整个读进内存了，那正是要防的事。
                    throw new TooBigException();
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    /** provider 报的大小，拿不到就是 null（云盘上很常见，不是错）。 */
    @Nullable
    private static Long declaredSize(Context app, Uri uri) {
        try (Cursor cursor = app.getContentResolver()
                .query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (column >= 0 && !cursor.isNull(column)) {
                    return cursor.getLong(column);
                }
            }
        } catch (RuntimeException unavailable) {
            // 查不到就问不出来，交给"边读边数"那条路，不因为这一步失败而放弃。
        }
        return null;
    }

    /** 显示名，只用于报错和预览时称呼这个文件。拿不到就给一个中性的名字，不编一个。 */
    private static String displayName(Context app, Uri uri) {
        try (Cursor cursor = app.getContentResolver()
                .query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0 && !cursor.isNull(column)) {
                    String name = cursor.getString(column);
                    if (name != null && !name.trim().isEmpty()) {
                        return name.trim();
                    }
                }
            }
        } catch (RuntimeException unavailable) {
            // 同上：拿不到名字不该让导入失败。
        }
        return app.getString(R.string.import_file_unknown);
    }

    private static String humanBytes(long bytes) {
        if (bytes >= 1024 * 1024) {
            return (bytes / (1024 * 1024)) + " MiB";
        }
        if (bytes >= 1024) {
            return (bytes / 1024) + " KB";
        }
        return bytes + " B";
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
        // 忙的时候连"选文件"也要禁掉：连点两次会读两份、弹两次预览。
        binding.importPick.setEnabled(!busy);
        binding.importCancel.setEnabled(!busy);
        binding.importGo.setEnabled(!busy && canImport(chosenPolicy()));
        binding.importPolicyAdd.setEnabled(!busy);
        binding.importPolicyReplace.setEnabled(!busy);
    }

    private void setStatus(@Nullable String status) {
        if (binding == null) {
            return;
        }
        binding.importStatus.setVisibility(status == null ? View.GONE : View.VISIBLE);
        if (status != null) {
            binding.importStatus.setText(status);
        }
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
