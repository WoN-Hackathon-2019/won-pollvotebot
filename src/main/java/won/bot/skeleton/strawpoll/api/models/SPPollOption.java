package won.bot.skeleton.strawpoll.api.models;

import lombok.Getter;
import lombok.Setter;

public class SPPollOption {

    @Getter
    @Setter
    private String title;

    @Getter
    @Setter
    private long votes;

    public SPPollOption(String option, long votes) {
        this.title = option;
        this.votes = votes;
    }

    @Override
    public String toString() {
        return "SPPollOption{" +
                "title='" + title + '\'' +
                ", votes=" + votes +
                '}';
    }
}