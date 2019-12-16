package won.bot.skeleton.impl;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.var;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import won.bot.framework.bot.base.EventBot;
import won.bot.framework.eventbot.EventListenerContext;
import won.bot.framework.eventbot.action.BaseEventBotAction;
import won.bot.framework.eventbot.behaviour.ExecuteWonMessageCommandBehaviour;
import won.bot.framework.eventbot.bus.EventBus;
import won.bot.framework.eventbot.event.Event;
import won.bot.framework.eventbot.event.impl.command.close.CloseCommandEvent;
import won.bot.framework.eventbot.event.impl.command.connect.ConnectCommandEvent;
import won.bot.framework.eventbot.event.impl.command.connect.ConnectCommandResultEvent;
import won.bot.framework.eventbot.event.impl.command.connect.ConnectCommandSuccessEvent;
import won.bot.framework.eventbot.event.impl.command.connectionmessage.ConnectionMessageCommandEvent;
import won.bot.framework.eventbot.event.impl.wonmessage.CloseFromOtherAtomEvent;
import won.bot.framework.eventbot.event.impl.wonmessage.ConnectFromOtherAtomEvent;
import won.bot.framework.eventbot.event.impl.wonmessage.HintFromMatcherEvent;
import won.bot.framework.eventbot.event.impl.wonmessage.MessageFromOtherAtomEvent;
import won.bot.framework.eventbot.filter.impl.AndFilter;
import won.bot.framework.eventbot.filter.impl.AtomUriInNamedListFilter;
import won.bot.framework.eventbot.filter.impl.CommandResultFilter;
import won.bot.framework.eventbot.filter.impl.NotFilter;
import won.bot.framework.eventbot.listener.EventListener;
import won.bot.framework.eventbot.listener.impl.ActionOnFirstEventListener;
import won.bot.framework.extensions.matcher.MatcherBehaviour;
import won.bot.framework.extensions.matcher.MatcherExtension;
import won.bot.framework.extensions.matcher.MatcherExtensionAtomCreatedEvent;
import won.bot.framework.extensions.serviceatom.ServiceAtomBehaviour;
import won.bot.framework.extensions.serviceatom.ServiceAtomExtension;
import won.bot.framework.extensions.textmessagecommand.TextMessageCommandBehaviour;
import won.bot.framework.extensions.textmessagecommand.UsageCommandEvent;
import won.bot.framework.extensions.textmessagecommand.command.EqualsTextMessageCommand;
import won.bot.framework.extensions.textmessagecommand.command.PatternMatcherTextMessageCommand;
import won.bot.framework.extensions.textmessagecommand.command.TextMessageCommand;
import won.bot.skeleton.action.IncomingGenericMessageAction;
import won.bot.skeleton.action.MatcherExtensionAtomCreatedAction;
import won.bot.skeleton.action.OpenConnectionAction;
import won.bot.skeleton.context.PollVoteBotContextWrapper;
import won.bot.skeleton.strawpoll.api.StrawpollAPI;
import won.bot.skeleton.strawpoll.api.models.SPPoll;
import won.bot.skeleton.strawpoll.api.models.SPPollOption;
import won.protocol.model.Connection;
import won.protocol.util.WonRdfUtils;

@Service
public class ChatService extends EventBot implements MatcherExtension, ServiceAtomExtension {
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private int registrationMatcherRetryInterval;
    private MatcherBehaviour matcherBehaviour;
    private ServiceAtomBehaviour serviceAtomBehaviour;

    private TextMessageCommandBehaviour textMessageCommandBehaviour;

    // bean setter, used by spring
    public void setRegistrationMatcherRetryInterval(final int registrationMatcherRetryInterval) {
        this.registrationMatcherRetryInterval = registrationMatcherRetryInterval;
    }

    @Override
    public ServiceAtomBehaviour getServiceAtomBehaviour() {
        return serviceAtomBehaviour;
    }

    @Override
    public MatcherBehaviour getMatcherBehaviour() {
        return matcherBehaviour;
    }

    @Override
    protected void initializeEventListeners() {
        final EventListenerContext ctx = getEventListenerContext();
        final EventBus bus = getEventBus();

        // define BotCommands for TextMessageCommandBehaviour
        ArrayList<TextMessageCommand> botCommands = new ArrayList<>();
        botCommands.add(new EqualsTextMessageCommand("random",
                "selects a random poll",
                "random",
                this::findRandomPoll));
        botCommands.add(new PatternMatcherTextMessageCommand("find <pollID>",
                "find a poll with the given ",
                Pattern.compile("^find\\s+(\\d+)$", Pattern.CASE_INSENSITIVE),
                this::findPollWithId));
        botCommands.add(new EqualsTextMessageCommand("close",
                "Closes the chat",
                "close"
                , this::closeConnection));
        botCommands.add(new PatternMatcherTextMessageCommand("vote <pollID> <optionIndex>",
                "vote in a poll with a certain option",
                Pattern.compile("^vote\\s+(\\d+)\\s+(\\d+)$", Pattern.CASE_INSENSITIVE),
                this::vote));

        // activate ServiceAtomBehaviour
        serviceAtomBehaviour = new ServiceAtomBehaviour(ctx);
        serviceAtomBehaviour.activate();

        // activate TextMessageCommandBehaviour
        textMessageCommandBehaviour = new TextMessageCommandBehaviour(ctx,
                botCommands.toArray(new TextMessageCommand[0]));
        textMessageCommandBehaviour.activate();

        // register listeners for event.impl.command events used to tell the bot to send
        // messages
        ExecuteWonMessageCommandBehaviour wonMessageCommandBehaviour = new ExecuteWonMessageCommandBehaviour(ctx);
        wonMessageCommandBehaviour.activate();

        // set up matching extension
        matcherBehaviour = new MatcherBehaviour(ctx, "DebugBotMatchingExtension", registrationMatcherRetryInterval);
        matcherBehaviour.activate();

        // filter to prevent reacting to own atoms
        NotFilter noOwnAtomsFilter = getNoOwnAtomsFilter();

        // filter to prevent reacting to serviceAtom <-> ownedAtom events;
        NotFilter noInternalServiceAtomEventFilter = getNoInternalServiceAtomEventFilter();

        // listen to new chat messages
        bus.subscribe(MessageFromOtherAtomEvent.class, new IncomingGenericMessageAction(ctx));

        // listen for the MatcherExtensionAtomCreatedEvent
        bus.subscribe(MatcherExtensionAtomCreatedEvent.class, new MatcherExtensionAtomCreatedAction(ctx));

        // Listen for new connections
        bus.subscribe(ConnectFromOtherAtomEvent.class, noInternalServiceAtomEventFilter, new OpenConnectionAction(ctx));
    }

    private void findRandomPoll(Connection connection) {
        final EventBus bus = getEventBus();
        bus.publish(new ConnectionMessageCommandEvent(connection, "find random poll"));
    }

    private void findPollWithId(Connection connection, Matcher matcher) {
        final EventBus bus = getEventBus();
        if (!matcher.matches()) {
            bus.publish(new ConnectionMessageCommandEvent(connection, "Invalid command!"));
            return;
        }
        long pollId = Long.parseLong(matcher.group(1));
        this.showOptions(connection, pollId);
    }

    private void closeConnection(Connection connection) {
        final EventBus bus = getEventBus();
        bus.publish(new ConnectionMessageCommandEvent(connection, "Bye, bye!"));
        bus.publish(new CloseCommandEvent(connection));
    }

    /**
     * displays all vote options of a poll
     * @param connection
     * @param pollID id of the poll whose options shall be displayed
     */
    private void showOptions(Connection connection, Long pollID) {
        final EventBus bus = getEventBus();

        try {
            SPPoll poll = StrawpollAPI.getResults(pollID);

            StringBuilder sb = new StringBuilder();
            sb.append(poll.getTitle());
            sb.append(" (");
            sb.append(poll.getId());
            sb.append(")");
            sb.append(System.lineSeparator());

            for(int i = 0; i < poll.getOptions().size(); i++) {
                sb.append(i);
                sb.append(") ");
                sb.append(poll.getOptions().get(i).getTitle());
                sb.append(" (votes: ");
                sb.append(poll.getOptions().get(i).getVotes());
                sb.append(")");
                sb.append(System.lineSeparator());
            }

            sb.append(System.lineSeparator());
            sb.append(System.lineSeparator());
            sb.append("in order to vote run the command 'vote <index>' where <index> shall be replaced by the displayed index of the answer");

            bus.publish(new ConnectionMessageCommandEvent(connection, sb.toString()));
        } catch (Exception e) {
            bus.publish(new ConnectionMessageCommandEvent(connection, System.lineSeparator() + "Whoops, it looks like we did not find a poll with the given id"));
            e.printStackTrace();
        }
    }

    /**
     * votes in a poll with a certain option
     * @param connection
     * @param matcher
     */
    private void vote(Connection connection, Matcher matcher) {

        final EventBus bus = getEventBus();
        if (!matcher.matches()) {
            bus.publish(new ConnectionMessageCommandEvent(connection, "Invalid command!"));
            return;
        }

        long pollID = Long.parseLong(matcher.group(1));
        int optionID = Integer.parseInt(matcher.group(2));

        try {
            StrawpollAPI.vote(pollID, optionID);
            bus.publish(new ConnectionMessageCommandEvent(connection, System.lineSeparator() + "You voted successfully"));
        }
        catch (Exception e) {
            bus.publish(new ConnectionMessageCommandEvent(connection, System.lineSeparator() + "Whoops, it looks like we did not find a poll with the given id or an index that was given"));
            e.printStackTrace();
        }




    }
}
