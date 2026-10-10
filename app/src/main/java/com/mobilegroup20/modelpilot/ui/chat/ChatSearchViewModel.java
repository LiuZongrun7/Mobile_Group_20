package com.mobilegroup20.modelpilot.ui.chat;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import com.mobilegroup20.modelpilot.chat.ChatSearchQuery;
import com.mobilegroup20.modelpilot.chat.local.ChatSearchResult;
import com.mobilegroup20.modelpilot.chat.local.ProjectEntity;
import com.mobilegroup20.modelpilot.data.RepositoryProvider;
import java.util.List;

/** Debounced Room queries; query/project survive rotation and saved-state restoration.
 * Result carries its query so an older response never replaces a newer input.
 */
public final class ChatSearchViewModel extends ViewModel {
    public static final class Result {
        public final ChatSearchQuery query;
        public final List<ChatSearchResult> rows;
        Result(ChatSearchQuery query,List<ChatSearchResult> rows){this.query=query;this.rows=rows;}
    }
    private final SavedStateHandle saved;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MutableLiveData<ChatSearchQuery> committed = new MutableLiveData<>();
    private Runnable pending;
    private final LiveData<Result> results;
    public ChatSearchViewModel(SavedStateHandle saved) {
        this.saved=saved;
        results=Transformations.switchMap(committed, q -> Transformations.map(
                RepositoryProvider.chats().searchHistoryLive(q.text,q.pattern,q.projectId,ChatSearchQuery.RESULT_LIMIT+1),
                rows -> new Result(q,rows)));
        committed.setValue(current());
    }
    public ChatSearchQuery current(){return new ChatSearchQuery(saved.get("query"),saved.get("project"));}
    public LiveData<Result> results(){return results;}
    public LiveData<List<ProjectEntity>> projects(){return RepositoryProvider.chats().projects();}
    public void input(String value,String projectId){
        saved.set("query",value);saved.set("project",projectId);
        if(pending!=null)main.removeCallbacks(pending);
        ChatSearchQuery query=current();
        pending=()->{committed.setValue(query);pending=null;};
        main.postDelayed(pending,query.text.isEmpty()?0:250);
    }
    @Override protected void onCleared(){if(pending!=null)main.removeCallbacks(pending);}
}
