package won.bot.skeleton.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import won.bot.framework.bot.base.EventBot;
import won.bot.framework.eventbot.EventListenerContext;
import won.bot.framework.eventbot.behaviour.ExecuteWonMessageCommandBehaviour;
import won.bot.framework.eventbot.bus.EventBus;
import won.bot.framework.eventbot.event.impl.command.close.CloseCommandEvent;
import won.bot.framework.eventbot.event.impl.command.connectionmessage.ConnectionMessageCommandEvent;
import won.bot.framework.eventbot.event.impl.wonmessage.ConnectFromOtherAtomEvent;
import won.bot.framework.eventbot.event.impl.wonmessage.MessageFromOtherAtomEvent;
import won.bot.framework.eventbot.filter.impl.NotFilter;
import won.bot.framework.extensions.matcher.MatcherBehaviour;
import won.bot.framework.extensions.matcher.MatcherExtension;
import won.bot.framework.extensions.matcher.MatcherExtensionAtomCreatedEvent;
import won.bot.framework.extensions.serviceatom.ServiceAtomBehaviour;
import won.bot.framework.extensions.serviceatom.ServiceAtomExtension;
import won.bot.framework.extensions.textmessagecommand.TextMessageCommandBehaviour;
import won.bot.framework.extensions.textmessagecommand.command.EqualsTextMessageCommand;
import won.bot.framework.extensions.textmessagecommand.command.PatternMatcherTextMessageCommand;
import won.bot.framework.extensions.textmessagecommand.command.TextMessageCommand;
import won.bot.skeleton.action.IncomingGenericMessageAction;
import won.bot.skeleton.action.MatcherExtensionAtomCreatedAction;
import won.bot.skeleton.action.OpenConnectionAction;
import won.protocol.model.Connection;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PollVoteBot extends EventBot implements MatcherExtension, ServiceAtomExtension {
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
        botCommands.add(new PatternMatcherTextMessageCommand("find <id>",
                "find a poll with the given ",
                Pattern.compile("^find\\s+(\\d+)$", Pattern.CASE_INSENSITIVE),
                this::findPollWithId));
        botCommands.add(new EqualsTextMessageCommand("close",
                "Closes the chat",
                "close",
                this::closeConnection));

        // activate ServiceAtomBehaviour
        serviceAtomBehaviour = new ServiceAtomBehaviour(ctx);
        serviceAtomBehaviour.activate();

        // activate TextMessageCommandBehaviour
        textMessageCommandBehaviour = new TextMessageCommandBehaviour(ctx, botCommands.toArray(new TextMessageCommand[0]));
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
        bus.publish(new ConnectionMessageCommandEvent(connection, "find poll with id " + pollId));
    }

    private void closeConnection(Connection connection) {
        final EventBus bus = getEventBus();
        bus.publish(new ConnectionMessageCommandEvent(connection, "Bye, bye!"));
        bus.publish(new CloseCommandEvent(connection));
    }
}
