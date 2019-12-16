package won.bot.skeleton.impl;

import won.bot.framework.bot.base.EventBot;
import won.bot.framework.eventbot.EventListenerContext;
import won.bot.framework.eventbot.action.BaseEventBotAction;
import won.bot.framework.eventbot.behaviour.ExecuteWonMessageCommandBehaviour;
import won.bot.framework.eventbot.bus.EventBus;
import won.bot.framework.eventbot.event.Event;
import won.bot.framework.eventbot.event.impl.command.close.CloseCommandEvent;
import won.bot.framework.eventbot.event.impl.command.connect.ConnectCommandSuccessEvent;
import won.bot.framework.eventbot.event.impl.command.connectionmessage.ConnectionMessageCommandEvent;
import won.bot.framework.eventbot.event.impl.command.connectionmessage.ConnectionMessageCommandSuccessEvent;
import won.bot.framework.eventbot.event.impl.command.create.CreateAtomCommandEvent;
import won.bot.framework.eventbot.event.impl.command.create.CreateAtomCommandFailureEvent;
import won.bot.framework.eventbot.event.impl.command.create.CreateAtomCommandSuccessEvent;
import won.bot.framework.eventbot.event.impl.wonmessage.ConnectFromOtherAtomEvent;
import won.bot.framework.eventbot.event.impl.wonmessage.HintFromMatcherEvent;
import won.bot.framework.eventbot.event.impl.wonmessage.MessageFromOtherAtomEvent;
import won.bot.framework.eventbot.filter.impl.CommandResultFilter;
import won.bot.framework.eventbot.filter.impl.NotFilter;
import won.bot.framework.eventbot.listener.EventListener;
import won.bot.framework.eventbot.listener.impl.ActionOnEventListener;
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
import won.protocol.model.Connection;
import won.protocol.util.DefaultAtomModelWrapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PollVoteBot extends EventBot implements MatcherExtension, ServiceAtomExtension {
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
        EventListenerContext ctx = getEventListenerContext();
        URI wonNodeUri = ctx.getNodeURISource().getNodeURI();
        URI atomURI = ctx.getWonNodeInformationService().generateAtomURI(wonNodeUri);

        // Set atom data - here only shown for commonly used (hence 'default') properties
        DefaultAtomModelWrapper atomWrapper = new DefaultAtomModelWrapper(atomURI);
        atomWrapper.addQuery(createSPARQLQuery());

        CreateAtomCommandEvent createCommand = new CreateAtomCommandEvent(atomWrapper.getDataset(), "atom_uris");
        bus.publish(createCommand);
        bus.subscribe(CreateAtomCommandSuccessEvent.class, new ActionOnEventListener(ctx, new CommandResultFilter(createCommand), new BaseEventBotAction(ctx) {

            @Override
            protected void doRun(Event event, EventListener eventListener) throws Exception {
                System.out.println("published atom:" + event.toString());
            }
        }));
        bus.subscribe(HintFromMatcherEvent.class, new ActionOnEventListener(ctx, "hint-reactor", new BaseEventBotAction(ctx) {

            @Override
            protected void doRun(Event event, EventListener eventListener) throws Exception {
                HintFromMatcherEvent hintEvent = (HintFromMatcherEvent) event;
                System.out.println(hintEvent.getHintTargetAtom());
            }
        }));
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
        final EventListenerContext ctx = getEventListenerContext();
        ConnectionMessageCommandEvent byeByeMessageEvent = new ConnectionMessageCommandEvent(connection, "Bye, bye!");
        bus.publish(byeByeMessageEvent);
        bus.subscribe(ConnectionMessageCommandSuccessEvent.class, new ActionOnFirstEventListener(ctx, new CommandResultFilter(byeByeMessageEvent),
                new BaseEventBotAction(ctx) {
                    @Override
                    protected void doRun(Event event, EventListener executingListener) {
                        ctx.getEventBus().publish(new CloseCommandEvent(connection));
                    }
                }));
    }

    private String createSPARQLQuery() {
        return new StringBuilder()
                .append("prefix won:   <https://w3id.org/won/core#>").append(System.lineSeparator())
                .append("prefix dc:    <http://purl.org/dc/elements/1.1/>").append(System.lineSeparator())
                .append("select ?description where {").append(System.lineSeparator())
                .append("  ?a dc:description ?description .").append(System.lineSeparator())
                .append("  ?a dc:title \"New Poll\" .").append(System.lineSeparator())
                .append("}")
                .toString();
    }
}
