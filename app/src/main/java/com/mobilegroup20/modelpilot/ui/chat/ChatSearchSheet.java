package com.mobilegroup20.modelpilot.ui.chat;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.chat.ChatSearchQuery;
import com.mobilegroup20.modelpilot.chat.local.ChatSearchResult;
import com.mobilegroup20.modelpilot.chat.local.ProjectEntity;
import com.mobilegroup20.modelpilot.databinding.SheetChatSearchBinding;
import com.mobilegroup20.modelpilot.databinding.ItemChatSearchBinding;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ChatSearchSheet extends BottomSheetDialogFragment {
    public static final String RESULT_KEY="history_search_open";
    public static final String CHAT_ID="chat_id";
    private SheetChatSearchBinding binding;
    private ChatSearchViewModel model;
    private final List<String> projectIds=new ArrayList<>();
    private boolean rebuildingProjects;
    private final ResultsAdapter adapter=new ResultsAdapter();
    public static void open(FragmentManager manager){
        if(manager.findFragmentByTag("history_search")==null)new ChatSearchSheet().show(manager,"history_search");
    }
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,@Nullable ViewGroup parent,@Nullable Bundle state){
        binding=SheetChatSearchBinding.inflate(inflater,parent,false);return binding.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view,@Nullable Bundle state){
        model=new ViewModelProvider(this).get(ChatSearchViewModel.class);
        binding.searchResults.setLayoutManager(new LinearLayoutManager(requireContext()));binding.searchResults.setAdapter(adapter);
        binding.searchQuery.setText(model.current().text);
        binding.searchQuery.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){submit(s.toString(),model.current().projectId);}
            public void afterTextChanged(Editable e){}
        });
        binding.searchProject.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View child,int pos,long id){
                if(!rebuildingProjects && pos<projectIds.size() && !java.util.Objects.equals(projectIds.get(pos),model.current().projectId))
                    submit(binding.searchQuery.getText()==null?"":binding.searchQuery.getText().toString(),projectIds.get(pos));
            }
        });
        model.projects().observe(getViewLifecycleOwner(),this::projects);
        model.results().observe(getViewLifecycleOwner(),result->{
            if(result==null || !result.query.sameAs(model.current()))return;
            List<ChatSearchResult> rows=result.rows==null?Collections.emptyList():result.rows;
            adapter.rows=new ArrayList<>(rows.subList(0,Math.min(rows.size(),ChatSearchQuery.RESULT_LIMIT)));adapter.notifyDataSetChanged();
            binding.searchStatus.setText(result.query.text.isEmpty()?getString(R.string.history_search_prompt)
                    :rows.isEmpty()?getString(R.string.history_search_empty)
                    :rows.size()>ChatSearchQuery.RESULT_LIMIT?getString(R.string.history_search_capped)
                    :getString(R.string.history_search_count,rows.size()));
        });
    }
    private void submit(String text,String projectId){
        adapter.rows=Collections.emptyList();adapter.notifyDataSetChanged();
        binding.searchStatus.setText(text.trim().isEmpty()?R.string.history_search_prompt:R.string.history_search_working);
        model.input(text,projectId);
    }
    private void projects(List<ProjectEntity> projects){
        rebuildingProjects=true;
        List<String> labels=new ArrayList<>();projectIds.clear();
        labels.add(getString(R.string.history_search_all));projectIds.add(null);
        labels.add(getString(R.string.history_search_unfiled));projectIds.add("");
        if(projects!=null)for(ProjectEntity p:projects){labels.add(p.name);projectIds.add(p.id);}
        String selected=model.current().projectId;int index=projectIds.indexOf(selected);
        binding.searchProject.setAdapter(new ArrayAdapter<>(requireContext(),android.R.layout.simple_spinner_dropdown_item,labels));
        binding.searchProject.setSelection(Math.max(0,index));rebuildingProjects=false;
        if(index<0)submit(model.current().text,null);
    }
    @Override public void onStart(){super.onStart();
        if(getDialog() instanceof com.google.android.material.bottomsheet.BottomSheetDialog){
            View sheet=getDialog().findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if(sheet!=null){sheet.getLayoutParams().height=ViewGroup.LayoutParams.MATCH_PARENT;
                com.google.android.material.bottomsheet.BottomSheetBehavior<View> behavior=com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet);
                behavior.setSkipCollapsed(true);behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);}
        }
    }
    @Override public void onDestroyView(){if(binding!=null)binding.searchResults.setAdapter(null);binding=null;super.onDestroyView();}
    private final class ResultsAdapter extends RecyclerView.Adapter<ResultHolder>{
        List<ChatSearchResult> rows=Collections.emptyList();
        public int getItemCount(){return rows.size();}
        @NonNull public ResultHolder onCreateViewHolder(@NonNull ViewGroup parent,int type){return new ResultHolder(ItemChatSearchBinding.inflate(LayoutInflater.from(parent.getContext()),parent,false));}
        public void onBindViewHolder(@NonNull ResultHolder holder,int position){
            ChatSearchResult row=rows.get(position);
            holder.ui.resultTitle.setText(row.title==null||row.title.isEmpty()?getString(R.string.chat_untitled):row.title);
            holder.ui.resultProject.setText(row.projectName==null?getString(R.string.history_search_unfiled):row.projectName);
            holder.ui.resultPreview.setText(row.preview==null?getString(R.string.history_search_title_match):row.preview);
            holder.itemView.setOnClickListener(v->{Bundle result=new Bundle();result.putString(CHAT_ID,row.chatId);
                getParentFragmentManager().setFragmentResult(RESULT_KEY,result);dismiss();});
        }
    }
    private static final class ResultHolder extends RecyclerView.ViewHolder{
        final ItemChatSearchBinding ui;ResultHolder(ItemChatSearchBinding ui){super(ui.getRoot());this.ui=ui;}
    }
}
