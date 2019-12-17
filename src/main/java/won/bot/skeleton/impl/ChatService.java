package won.bot.skeleton.impl;

import org.apache.jena.query.Dataset;
import org.apache.jena.rdf.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import won.bot.framework.bot.base.EventBot;
import won.bot.framework.eventbot.EventListenerContext;
import won.bot.framework.eventbot.action.BaseEventBotAction;
import won.bot.framework.eventbot.behaviour.ExecuteWonMessageCommandBehaviour;
import won.bot.framework.eventbot.bus.EventBus;
import won.bot.framework.eventbot.event.Event;
import won.bot.framework.eventbot.event.impl.atomlifecycle.AtomCreatedEvent;
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
import won.bot.framework.extensions.matcher.MatcherExtensionAtomCreatedEvent;
import won.bot.framework.extensions.serviceatom.ServiceAtomBehaviour;
import won.bot.framework.extensions.serviceatom.ServiceAtomExtension;
import won.bot.framework.extensions.textmessagecommand.TextMessageCommandBehaviour;
import won.bot.framework.extensions.textmessagecommand.command.EqualsTextMessageCommand;
import won.bot.framework.extensions.textmessagecommand.command.PatternMatcherTextMessageCommand;
import won.bot.framework.extensions.textmessagecommand.command.TextMessageCommand;
import won.bot.skeleton.action.OpenConnectionAction;
import won.bot.skeleton.model.SCHEMA_EXTENDED;
import won.bot.skeleton.strawpoll.api.StrawpollAPI;
import won.bot.skeleton.strawpoll.api.models.SPPoll;
import won.protocol.exception.IncorrectPropertyCountException;
import won.protocol.model.Connection;
import won.protocol.util.DefaultAtomModelWrapper;
import won.protocol.vocabulary.SCHEMA;
import won.protocol.vocabulary.WON;
import won.protocol.vocabulary.WONMATCH;

import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
                    for (Connection con : waitingRandomConnections) {
                        sendRandomPollToConnection(con);
                    }
                    waitingRandomConnections.clear();
                }
            }
        }));
        bus.subscribe(MatcherExtensionAtomCreatedEvent.class, new ActionOnEventListener(ctx, new BaseEventBotAction(ctx) {
            @Override
            protected void doRun(Event event, EventListener executingListener) throws Exception {
                DefaultAtomModelWrapper wrapper = new DefaultAtomModelWrapper(((MatcherExtensionAtomCreatedEvent) event).getAtomData());
                MatcherExtensionAtomCreatedEvent mExtensionAtomCreated = (MatcherExtensionAtomCreatedEvent) event;
                if (wrapper.getAllTags().contains("PollAtom")) {
                    createVoteAtomForPollAtom(mExtensionAtomCreated.getAtomURI());
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

        String rawName = null, rawId = null;

        try {
            rawName = defaultAtomModelWrapper.getContentPropertyStringValue(SCHEMA.NAME);
            rawId = defaultAtomModelWrapper.getContentPropertyStringValue(SCHEMA_EXTENDED.ID);

            for (Resource node : defaultAtomModelWrapper.getSeeksNodes()) {
                if (rawName == null) {
                    rawName = defaultAtomModelWrapper.getContentPropertyStringValue(node, SCHEMA.NAME);
                } else if (rawId == null) {
                    rawId = defaultAtomModelWrapper.getContentPropertyStringValue(node, SCHEMA_EXTENDED.ID);
                } else {
                    break;
                }
            }
        } catch (IncorrectPropertyCountException e) {
            // Silently ignore property count warnings
        }
        if (rawName == null || rawId == null) {
            System.out.println("Could not find name or id in PollAtom with URI: " + pollAtomUri + ". Ignoring...");
            return;
        }
        long pollId = Long.parseLong(rawId);
        String pollName = rawName;
        pollAtomsIndex.put(pollId, pollAtomUri);

        boolean hasConnection = true; // TODO: check if poll atom has a vote atom connected

        if (!hasConnection) {
            // Create VoteAtom
            final EventBus bus = getEventBus();
            EventListenerContext ctx = getEventListenerContext();

            // Create Vote Atom
            URI wonNodeUri = ctx.getNodeURISource().getNodeURI();
            URI atomURI = ctx.getWonNodeInformationService().generateAtomURI(wonNodeUri);
            DefaultAtomModelWrapper atomWrapper = new DefaultAtomModelWrapper(atomURI);
            atomWrapper.addPropertyStringValue(SCHEMA.NAME, "Vote for: " + pollName);

            //atomWrapper.addPropertyStringValue(); // TODO: add reference to poll atom

            CreateAtomCommandEvent createVoteAtomEvent = new CreateAtomCommandEvent(atomWrapper.getDataset(), "atom_uris");
            bus.publish(createVoteAtomEvent);
            bus.subscribe(CreateAtomCommandSuccessEvent.class, new ActionOnEventListener(ctx, new CommandResultFilter(createVoteAtomEvent), new BaseEventBotAction(ctx) {

                @Override
                protected void doRun(Event event, EventListener eventListener) throws Exception {
                    CreateAtomCommandSuccessEvent createAtomCommandEvent = (CreateAtomCommandSuccessEvent) event;
                    System.out.println("Successfully created vote atom with URI: " + createAtomCommandEvent.getAtomURI());
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
        this.showOptions(connection, pollId);
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
            bus.publish(new ConnectionMessageCommandEvent(connection, System.lineSeparator() + "Whoops, it looks like we did not find a poll with the given id or index"));
            e.printStackTrace();
        }

    }
}
