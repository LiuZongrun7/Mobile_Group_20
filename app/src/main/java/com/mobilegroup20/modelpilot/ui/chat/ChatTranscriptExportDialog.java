package com.mobilegroup20.modelpilot.ui.chat;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.data.RepositoryProvider;
import com.mobilegroup20.modelpilot.data.export.PydanticTranscriptWriter;
import com.mobilegroup20.modelpilot.data.export.ExportArtifact;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-confirmed single-chat NDJSON export. System picker grants access only to the selected file.
 * Pending bytes stay in a private cache file across rotation, rather than in a Bundle.
 */
public final class ChatTranscriptExportDialog extends DialogFragment {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private String pending;
    private boolean busy;
    public static void open(FragmentManager manager, String chatId) {
        if (manager.findFragmentByTag("chat_transcript_export") != null) return;
        ChatTranscriptExportDialog dialog = new ChatTranscriptExportDialog();
        Bundle args = new Bundle(); args.putString("chat",chatId);dialog.setArguments(args);
        dialog.show(manager,"chat_transcript_export");
    }
    private final ActivityResultLauncher<Intent> saver = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                Uri uri = result.getData() == null ? null : result.getData().getData();
                if (result.getResultCode() != Activity.RESULT_OK || uri == null) {
                    setStatus(R.string.transcript_cancelled); return;
                }
                Context app = requireContext().getApplicationContext();
                String name = pending;
                if (name == null || !name.matches("[a-f0-9-]+\\.ndjson")) {
                    setStatus(R.string.transcript_failed); return;
                }
                setBusy(true);
                io.execute(() -> {
                    boolean success = false;
                    File file = new File(new File(app.getCacheDir(), "chat-transcripts"), name);
                    try (FileInputStream in = new FileInputStream(file);
                         OutputStream out = app.getContentResolver().openOutputStream(uri,"wt")) {
                        if (out == null) throw new java.io.IOException();
                        byte[] buffer = new byte[8192];int count;
                        while ((count=in.read(buffer))!=-1) out.write(buffer,0,count);
                        success = true;
                    } catch (Exception failure) { success = false; /* Do not expose transcript contents. */ }
                    boolean saved = success;
                    if (saved) file.delete();
                    main.post(() -> { if (isAdded()) {
                        if (saved) pending=null;
                        setBusy(false);setStatus(saved ? R.string.transcript_saved : R.string.transcript_failed);
                    }});
                });
            });
    @NonNull @Override public Dialog onCreateDialog(@Nullable Bundle saved) {
        pending = saved == null ? null : saved.getString("pending");
        status = new TextView(requireContext());
        int pad=(int)(20*getResources().getDisplayMetrics().density);status.setPadding(pad,pad,pad,0);
        setStatus(R.string.transcript_note);
        return new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.transcript_title)
                .setView(status).setPositiveButton(R.string.transcript_save,null)
                .setNegativeButton(android.R.string.cancel,null).create();
    }
    @Override public void onStart() {
        super.onStart();
        ((androidx.appcompat.app.AlertDialog)requireDialog()).getButton(-1).setOnClickListener(v -> generate());
        setBusy(busy);
    }
    private void generate() {
        if (busy) return;
        Context app=requireContext().getApplicationContext();
        String id=requireArguments().getString("chat");
        setBusy(true);setStatus(R.string.transcript_working);
        io.execute(() -> {
            try {
                ExportArtifact artifact=PydanticTranscriptWriter.write(id,RepositoryProvider.chats().readHistory(id).messages);
                File dir=new File(app.getCacheDir(),"chat-transcripts");
                if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException();
                File[] stale=dir.listFiles();
                if (stale!=null) for(File file:stale) {
                    if (file.getName().matches("[a-f0-9-]+\\.ndjson") && System.currentTimeMillis()-file.lastModified()>86400000L) file.delete();
                }
                String name=UUID.randomUUID()+".ndjson";
                try(FileOutputStream out=new FileOutputStream(new File(dir,name))) {out.write(artifact.bytes);}
                main.post(() -> {if (isAdded()) {
                    pending=name;setBusy(false);
                    saver.launch(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType(artifact.mimeType()).putExtra(Intent.EXTRA_TITLE,artifact.fileName));
                }});
            } catch(Exception failure) {
                main.post(() -> {if(isAdded()){setBusy(false);setStatus(R.string.transcript_failed);}});
            }
        });
    }
    private void setStatus(int string){if(status!=null)status.setText(string);}
    private void setBusy(boolean next){busy=next;if(getDialog() instanceof androidx.appcompat.app.AlertDialog){
        android.widget.Button button=((androidx.appcompat.app.AlertDialog)getDialog()).getButton(-1);
        if(button!=null)button.setEnabled(!next);
    }}
    @Override public void onSaveInstanceState(@NonNull Bundle out){super.onSaveInstanceState(out);out.putString("pending",pending);}
    @Override public void onDestroy(){io.shutdown();super.onDestroy();}
}
