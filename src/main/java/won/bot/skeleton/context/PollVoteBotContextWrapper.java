package won.bot.skeleton.context;

import won.bot.framework.bot.context.BotContext;
import won.bot.framework.extensions.serviceatom.ServiceAtomEnabledBotContextWrapper;

import java.net.URI;
import java.util.*;

public class PollVoteBotContextWrapper extends ServiceAtomEnabledBotContextWrapper {

    private final String createdPollAtomUris;

    public PollVoteBotContextWrapper(BotContext botContext, String botName) {
        super(botContext, botName);
        this.createdPollAtomUris = botName + ":createdPollAtomUris";
    }

    public boolean hasCreatedVoteAtomForPollAtomWithURI(URI pollAtomUri) {
        Map<String, List<Object>> createdPollAtoms = getBotContext().loadListMap(createdPollAtomUris);
        return createdPollAtoms.containsKey(pollAtomUri.toString());
    }

    public void createdVoteAtomForPollAtomWithURI(URI voteAtomUri, URI pollAtomUri) {
        getBotContext().addToListMap(createdPollAtomUris, pollAtomUri.toString(), voteAtomUri);
    }
}
