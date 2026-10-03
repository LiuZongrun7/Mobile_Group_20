package com.mobilegroup20.modelpilot.ui.chat;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.chat.ModelChoices;
import com.mobilegroup20.modelpilot.chat.ProviderRegistry;
import com.mobilegroup20.modelpilot.chat.TaskKind;
import com.mobilegroup20.modelpilot.data.ProviderKeys;
import com.mobilegroup20.modelpilot.data.RepositoryProvider;
import com.mobilegroup20.modelpilot.databinding.ItemModelChoiceBinding;
import com.mobilegroup20.modelpilot.databinding.SheetModelBinding;

import java.util.List;

/**
 * 模型选择弹层（设计稿 `models.png`）。
 *
 * <p><b>这一层只做三件事</b>：问 {@link ModelChoices} 该显示哪三段、把三段画出来、
 * 把用户点的那个发回去。**判断"谁能选、谁置灰、为什么"全在 ModelChoices 里**，
 * 因为那些判断要被单测钉住，而界面测不了。
 *
 * <p>结果通过 `FragmentResult` 发给开它的界面（{@link #RESULT_KEY}）：
 * `providerId`/`modelId` 都为 null 表示选了 Auto。
 */
public final class ModelSheetFragment extends BottomSheetDialogFragment {

    public static final String RESULT_KEY = "model_choice";
    public static final String BUNDLE_PROVIDER = "provider_id";
    public static final String BUNDLE_MODEL = "model_id";

    private static final String ARG_PROVIDER = "provider";
    private static final String ARG_MODEL = "model";
    private static final String ARG_TASK = "task";

    /**
     * 打开弹层。
     *
     * @param providerId 当前手动选中的家；null = 当前是 Auto
     * @param task       这次要做什么（决定谁进置灰段）。默认文本任务——
     *                   首页还没有附件，等对话页接上附件后由它传真的任务类型。
     */
    public static void show(FragmentManager fm, @Nullable String providerId,
                            @Nullable String modelId, TaskKind task) {
        Bundle args = new Bundle();
        args.putString(ARG_PROVIDER, providerId);
        args.putString(ARG_MODEL, modelId);
        args.putString(ARG_TASK, (task == null ? TaskKind.TEXT : task).name());
        ModelSheetFragment sheet = new ModelSheetFragment();
        sheet.setArguments(args);
        sheet.show(fm, "model_sheet");
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        SheetModelBinding binding = SheetModelBinding.inflate(inflater, parent, false);
        Bundle args = requireArguments();
        String manualProvider = args.getString(ARG_PROVIDER);
        String manualModel = args.getString(ARG_MODEL);
        TaskKind task = TaskKind.valueOf(args.getString(ARG_TASK, TaskKind.TEXT.name()));

        ProviderRegistry registry = RepositoryProvider.providers();
        List<String> enabled = ProviderKeys.configuredProviders(requireContext());
        List<ModelChoices.Group> groups =
                ModelChoices.build(registry, enabled, task, manualProvider, manualModel);

        binding.modelTask.setText(taskLabel(task));
        binding.modelClose.setOnClickListener(v -> dismiss());
        binding.modelNote.setText(R.string.chat_model_note);

        boolean anyChoice = false;
        for (ModelChoices.Group group : groups) {
            LinearLayout target;
            switch (group.kind) {
                case AUTO:
                    target = binding.modelAutoContainer;
                    break;
                case MANUAL:
                    target = binding.modelManualContainer;
                    break;
                default:
                    target = binding.modelUnavailableContainer;
                    break;
            }
            target.removeAllViews();
            for (ModelChoices.Choice choice : group.choices) {
                if (group.kind != ModelChoices.Group.Kind.AUTO) {
                    anyChoice = anyChoice || choice.selectable;
                }
                target.addView(choiceRow(target, group.kind, choice,
                        manualProvider, manualModel));
            }
            // 空段的标题不显示：一个"Unavailable for this task"标题下面什么都没有，
            // 会让人以为这一段没加载出来。
            int labelVisibility = group.choices.isEmpty() ? View.GONE : View.VISIBLE;
            if (group.kind == ModelChoices.Group.Kind.MANUAL) {
                binding.modelManualLabel.setVisibility(labelVisibility);
            } else if (group.kind == ModelChoices.Group.Kind.UNAVAILABLE) {
                binding.modelUnavailableLabel.setVisibility(labelVisibility);
            }
        }
        // 一个 key 都没填：Auto 那一段还在（它不需要 key 就能"选"，只是发不出去），
        // 所以要单独说清楚"先去哪里填"。
        binding.modelEmpty.setVisibility(
                anyChoice || !enabled.isEmpty() ? View.GONE : View.VISIBLE);
        return binding.getRoot();
    }

    private View choiceRow(LinearLayout parent, ModelChoices.Group.Kind kind,
                           ModelChoices.Choice choice,
                           @Nullable String manualProvider, @Nullable String manualModel) {
        ItemModelChoiceBinding row = ItemModelChoiceBinding.inflate(getLayoutInflater(), parent,
                false);
        row.modelTitle.setText(choice.title);
        row.modelSubtitle.setText(choice.subtitle);

        boolean isAuto = kind == ModelChoices.Group.Kind.AUTO;
        boolean selected = isAuto
                ? manualProvider == null
                : choice.selectable && eq(choice.providerId, manualProvider)
                        && eq(choice.modelId, manualModel);

        if (isAuto) {
            row.getRoot().setBackgroundResource(R.drawable.bg_chat_choice_auto);
            row.modelMark.setBackgroundResource(R.drawable.bg_chat_choice_auto);
            row.modelMark.setText("\u2726");
            row.modelMark.setTextColor(ContextCompat.getColor(requireContext(), R.color.chat_auto_ink));
        } else {
            ProviderPalette.Colors colors = ProviderPalette.of(choice.providerId);
            row.modelMark.getBackground().mutate().setTint(colors.markBackground);
            row.modelMark.setTextColor(colors.markText);
            row.modelMark.setText(ProviderPalette.mark(choice.providerId, choice.modelId));
        }

        row.modelCheck.setVisibility(selected && choice.selectable ? View.VISIBLE : View.GONE);
        row.modelRadio.setVisibility(selected && choice.selectable ? View.GONE : View.VISIBLE);
        if (!choice.selectable) {
            // 置灰：**名字照样看得清**（用户要能找到"我配的那个怎么不能选"），
            // 只有副标题和右边那个圈变浅，明确表示"现在点不动"。
            int disabled = ContextCompat.getColor(requireContext(), R.color.chat_disabled_ink);
            row.modelTitle.setTextColor(disabled);
            row.modelSubtitle.setTextColor(disabled);
            row.modelMark.setAlpha(0.45f);
            row.modelRadio.setAlpha(0.5f);
            row.getRoot().setClickable(false);
            row.getRoot().setFocusable(false);
            row.getRoot().setBackground(null);
        } else {
            row.getRoot().setOnClickListener(v -> {
                Bundle result = new Bundle();
                result.putString(BUNDLE_PROVIDER, choice.providerId);
                result.putString(BUNDLE_MODEL, choice.modelId);
                getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
                dismiss();
            });
        }
        return row.getRoot();
    }

    /** 任务胶囊上的那行字。1 任务名 2 补充说明。 */
    private String taskLabel(TaskKind task) {
        switch (task) {
            case IMAGE:
                return getString(R.string.chat_task_image);
            case PDF:
                return getString(R.string.chat_task_pdf);
            case TOOLS:
                return getString(R.string.chat_task_tools);
            default:
                return getString(R.string.chat_task_text);
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
