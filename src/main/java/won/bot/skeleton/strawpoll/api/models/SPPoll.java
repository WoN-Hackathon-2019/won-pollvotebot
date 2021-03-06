package won.bot.skeleton.strawpoll.api.models;

import lombok.Getter;
import lombok.Setter;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class SPPoll {

    @Getter
    @Setter
    private long id;

    @Getter
    @Setter
    private String title;

    @Getter
    @Setter
    private boolean multi;

    @Getter
    @Setter
    private List<SPPollOption> options = new ArrayList<>();

    public SPPoll(JSONObject json) {
        this.id = (long) json.get("id");
        this.title = (String) json.get("title");
        this.multi = (boolean) json.get("multi");

        JSONArray options = (JSONArray) json.get("options");
        JSONArray votes = (JSONArray) json.get("votes");

        Iterator it = options.iterator();
        Iterator votesIt = votes.iterator();
        while (it.hasNext()) {
            String option = (String) it.next();
            long vote = (long) votesIt.next();
            this.options.add(new SPPollOption(option, vote));
        }
    }

    @Override
    public String toString() {
        return "SPPoll{" +
                "id=" + id +
                ", title='" + title + '\'' +
                ", multi=" + multi +
                ", options=" + options +
                '}';
    }
}
