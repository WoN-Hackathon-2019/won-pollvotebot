package won.bot.skeleton.strawpoll.api.models;

public class SPPollOption {

    private String title;
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