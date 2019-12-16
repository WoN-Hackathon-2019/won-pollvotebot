package won.bot.skeleton.impl;

import com.sun.jndi.toolkit.url.Uri;
import org.apache.jena.query.Dataset;
import won.bot.framework.bot.base.EventBot;
import won.bot.framework.eventbot.EventListenerContext;
import won.bot.framework.eventbot.action.BaseEventBotAction;
import won.bot.framework.eventbot.behaviour.ExecuteWonMessageCommandBehaviour;
import won.bot.framework.eventbot.bus.EventBus;
import won.bot.framework.eventbot.event.Event;
import won.bot.framework.eventbot.event.impl.command.close.CloseCommandEvent;
import won.bot.framework.eventbot.event.impl.command.connectionmessage.ConnectionMessageCommandEvent;
import won.bot.framework.eventbot.event.impl.command.connectionmessage.ConnectionMessageCommandSuccessEvent;
import won.bot.framework.eventbot.event.impl.command.create.CreateAtomCommandEvent;
import won.bot.framework.eventbot.event.impl.command.create.CreateAtomCommandSuccessEvent;
import won.bot.framework.eventbot.event.impl.wonmessage.ConnectFromOtherAtomEvent;
import won.bot.framework.eventbot.event.impl.wonmessage.HintFromMatcherEvent;
import won.bot.framework.eventbot.filter.impl.CommandResultFilter;
import won.bot.framework.eventbot.filter.impl.NotFilter;
import won.bot.framework.eventbot.listener.EventListener;
import won.bot.framework.eventbot.listener.impl.ActionOnEventListener;
import won.bot.framework.eventbot.listener.impl.ActionOnFirstEventListener;
import won.bot.framework.extensions.matcher.MatcherBehaviour;
import won.bot.framework.extensions.matcher.MatcherExtension;
import won.bot.framework.extensions.serviceatom.ServiceAtomBehaviour;
import won.bot.framework.extensions.serviceatom.ServiceAtomExtension;
import won.bot.framework.extensions.textmessagecommand.TextMessageCommandBehaviour;
import won.bot.framework.extensions.textmessagecommand.command.EqualsTextMessageCommand;
import won.bot.framework.extensions.textmessagecommand.command.PatternMatcherTextMessageCommand;
import won.bot.framework.extensions.textmessagecommand.command.TextMessageCommand;
import won.bot.skeleton.action.OpenConnectionAction;
import won.protocol.message.WonMessage;
import won.protocol.model.Connection;
import won.protocol.util.DefaultAtomModelWrapper;
import won.protocol.util.linkeddata.LinkedDataSource;
import won.protocol.util.linkeddata.WonLinkedDataUtils;
import won.protocol.vocabulary.SCHEMA;
import won.protocol.vocabulary.WONMATCH;
import won.protocol.vocabulary.WXCHAT;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
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

        // Listen for new connections
        bus.subscribe(ConnectFromOtherAtomEvent.class, noInternalServiceAtomEventFilter, new OpenConnectionAction(ctx));

        createPollAtomListener();
    }

    private ConcurrentHashMap<Long, URI> pollAtomsIndex = new ConcurrentHashMap<>();
    private List<Connection> waitingRandomConnections = new ArrayList<>();

    private void createPollAtomListener() {
        final EventBus bus = getEventBus();
        EventListenerContext ctx = getEventListenerContext();
        URI wonNodeUri = ctx.getNodeURISource().getNodeURI();
        URI atomURI = ctx.getWonNodeInformationService().generateAtomURI(wonNodeUri);

        // Set atom data - here only shown for commonly used (hence 'default') properties
        DefaultAtomModelWrapper atomWrapper = new DefaultAtomModelWrapper(atomURI);
        atomWrapper.addFlag(WONMATCH.NoHintForCounterpart);
        atomWrapper.addQuery(createSPARQLQuery());

        CreateAtomCommandEvent fetchPollAtomsRequestEvent = new CreateAtomCommandEvent(atomWrapper.getDataset(), "atom_uris");
        bus.publish(fetchPollAtomsRequestEvent);
        bus.subscribe(CreateAtomCommandSuccessEvent.class, new ActionOnEventListener(ctx, new CommandResultFilter(fetchPollAtomsRequestEvent), new BaseEventBotAction(ctx) {

            @Override
            protected void doRun(Event event, EventListener eventListener) throws Exception {
                System.out.println("published atom:" + event.toString());
            }
        }));
        bus.subscribe(HintFromMatcherEvent.class, new ActionOnEventListener(ctx, new BaseEventBotAction(ctx) {

            @Override
            protected void doRun(Event event, EventListener eventListener) throws Exception {
                HintFromMatcherEvent hintEvent = (HintFromMatcherEvent) event;
                createVoteAtomForPollAtom(hintEvent.getHintTargetAtom());
                if (!waitingRandomConnections.isEmpty()) {
                    for (Connection con: waitingRandomConnections) {
                        sendRandomPollToConnection(con);
                    }
                    waitingRandomConnections.clear();
                }
            }
        }));
    }

    private void findRandomPoll(Connection connection) {
        final EventBus bus = getEventBus();
        if (pollAtomsIndex.isEmpty()) {
            waitingRandomConnections.add(connection);
            bus.publish(new ConnectionMessageCommandEvent(connection, "Waiting for poll..."));
        } else {
            sendRandomPollToConnection(connection);
        }
    }

    private void sendRandomPollToConnection(Connection connection) {
        URI[] uris = pollAtomsIndex.values().toArray(new URI[0]);
        int bounds = pollAtomsIndex.values().size();
        int random = new Random().nextInt(bounds);
        URI uri = uris[random];

        final EventBus bus = getEventBus();
        bus.publish(new ConnectionMessageCommandEvent(connection, "Found poll atom: " + uri));
    }

    private void createVoteAtomForPollAtom(URI pollAtomUri) {
        Dataset atomData = getEventListenerContext().getLinkedDataSource().getDataForResource(pollAtomUri);
        DefaultAtomModelWrapper defaultAtomModelWrapper = new DefaultAtomModelWrapper(atomData);
        System.out.println(defaultAtomModelWrapper.getContentPropertyObjects("s:name"));
        // TODO: System.out.println(defaultAtomModelWrapper.get());
        long pollId = 123L; // TODO: Get id from poll Atom body
        String pollName = "ASDSF"; // TODO: get name from poll atom body
        pollAtomsIndex.put(pollId, pollAtomUri);

        boolean hasConnection = false; // TODO: check if poll atom has a vote atom connected

        if (!hasConnection) {
            // Create VoteAtom
            final EventBus bus = getEventBus();
            EventListenerContext ctx = getEventListenerContext();

            // Create Vote Atom
            URI wonNodeUri = ctx.getNodeURISource().getNodeURI();
            URI atomURI = ctx.getWonNodeInformationService().generateAtomURI(wonNodeUri);
            DefaultAtomModelWrapper atomWrapper = new DefaultAtomModelWrapper(atomURI);
            atomWrapper.addPropertyStringValue(SCHEMA.NAME, "Vote for: " + pollName);
            CreateAtomCommandEvent createVoteAtomEvent = new CreateAtomCommandEvent(atomWrapper.getDataset(), "atom_uris");
            bus.publish(createVoteAtomEvent);
            bus.subscribe(CreateAtomCommandSuccessEvent.class, new ActionOnEventListener(ctx, new CommandResultFilter(createVoteAtomEvent), new BaseEventBotAction(ctx) {

                @Override
                protected void doRun(Event event, EventListener eventListener) throws Exception {
                    CreateAtomCommandEvent createAtomCommandEvent = (CreateAtomCommandEvent) event;
                    System.out.println("published atom:" + event.toString());
                    // TODO: Create connection VoteAtom --> PollATom per pollAtomUri

//        String message = "Hello, let's connect!"; //optional welcome message
//        ConnectCommandEvent connectCommandEvent = new ConnectCommandEvent(
//                senderSocketURI,recipientSocketURI, message);
//        getEventBus().publish(connectCommandEvent);
                }
            }));
        }
    }

    private void findPollWithId(Connection connection, Matcher matcher) {
        final EventBus bus = getEventBus();
        if (!matcher.matches()) {
            bus.publish(new ConnectionMessageCommandEvent(connection, "Invalid command!"));
            return;
        }
        long pollId = Long.parseLong(matcher.group(1));
        URI atomUri = pollAtomsIndex.get(pollId);
        if (atomUri == null) {
            bus.publish(new ConnectionMessageCommandEvent(connection, "Could not find poll with id: " + pollId));
        } else {
            bus.publish(new ConnectionMessageCommandEvent(connection, "Atom: " + atomUri.toString()));
        }
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
                .append("prefix won: <https://w3id.org/won/core#>").append(System.lineSeparator())
                .append("prefix dc:  <http://purl.org/dc/elements/1.1/>").append(System.lineSeparator())
                .append("select distinct ?result where {").append(System.lineSeparator())
                .append("  ?result a won:PollAtom .").append(System.lineSeparator())
                .append("}")
                .toString();
    }
}
