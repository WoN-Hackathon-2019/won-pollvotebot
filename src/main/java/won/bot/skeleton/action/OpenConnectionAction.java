package won.bot.skeleton.action;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import won.bot.framework.eventbot.EventListenerContext;
import won.bot.framework.eventbot.action.BaseEventBotAction;
import won.bot.framework.eventbot.bus.EventBus;
import won.bot.framework.eventbot.event.Event;
import won.bot.framework.eventbot.event.impl.command.MessageCommandEvent;
import won.bot.framework.eventbot.event.impl.command.connect.ConnectCommandEvent;
import won.bot.framework.eventbot.event.impl.command.connect.ConnectCommandSuccessEvent;
import won.bot.framework.eventbot.event.impl.wonmessage.ConnectFromOtherAtomEvent;
import won.bot.framework.eventbot.filter.impl.CommandResultFilter;
import won.bot.framework.eventbot.listener.EventListener;
import won.bot.framework.eventbot.listener.impl.ActionOnFirstEventListener;
import won.bot.framework.extensions.textmessagecommand.UsageCommandEvent;
import won.protocol.model.Connection;
import won.protocol.util.linkeddata.WonLinkedDataUtils;

import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.util.Optional;

public class OpenConnectionAction extends BaseEventBotAction {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    public OpenConnectionAction(EventListenerContext eventListenerContext) {
        super(eventListenerContext);
    }

    @Override
    protected void doRun(Event event, EventListener executingListener) {
        EventListenerContext ctx = getEventListenerContext();
        EventBus bus = ctx.getEventBus();
        ConnectFromOtherAtomEvent connectFromOtherAtomEvent = (ConnectFromOtherAtomEvent) event;
        try {
            String message = "Hi there! I am the PollVoteBot. I am here to help you giving your opinion. To see what I can do, type 'usage'.";

            final ConnectCommandEvent connectCommandEvent = new ConnectCommandEvent(
                    connectFromOtherAtomEvent.getRecipientSocket(),
                    connectFromOtherAtomEvent.getSenderSocket(), message);
            bus.subscribe(ConnectCommandSuccessEvent.class, new ActionOnFirstEventListener(ctx, new CommandResultFilter(connectCommandEvent),
                    new BaseEventBotAction(ctx) {
                        @Override
                        protected void doRun(Event event, EventListener executingListener) {
                            final ConnectCommandSuccessEvent successEvent = (ConnectCommandSuccessEvent) event;
                            logger.info(successEvent.getCon().toString());
                            UsageCommandEvent usageCommandEvent = new UsageCommandEvent(successEvent.getCon());
                            ctx.getEventBus().publish(usageCommandEvent);
                        }
                    }));
            bus.publish(connectCommandEvent);
        } catch (Exception te) {
            logger.error(te.getMessage(), te);
        }
    }
}