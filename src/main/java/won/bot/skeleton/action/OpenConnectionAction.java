package won.bot.skeleton.action;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import won.bot.framework.eventbot.EventListenerContext;
import won.bot.framework.eventbot.action.BaseEventBotAction;
import won.bot.framework.eventbot.event.Event;
import won.bot.framework.eventbot.event.impl.command.connect.ConnectCommandEvent;
import won.bot.framework.eventbot.event.impl.wonmessage.ConnectFromOtherAtomEvent;
import won.bot.framework.eventbot.listener.EventListener;

import java.lang.invoke.MethodHandles;

public class OpenConnectionAction extends BaseEventBotAction {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    public OpenConnectionAction(EventListenerContext eventListenerContext) {
        super(eventListenerContext);
    }

    @Override
    protected void doRun(Event event, EventListener executingListener) {
        EventListenerContext ctx = getEventListenerContext();
        ConnectFromOtherAtomEvent connectFromOtherAtomEvent = (ConnectFromOtherAtomEvent) event;
        try {
            String message = "Hi there! I am the PollVoteBot. I am here to help you giving your opinion. To see what I can do, type 'dude'.";
            final ConnectCommandEvent connectCommandEvent = new ConnectCommandEvent(
                    connectFromOtherAtomEvent.getRecipientSocket(),
                    connectFromOtherAtomEvent.getSenderSocket(), message);
            ctx.getEventBus().publish(connectCommandEvent);
        } catch (Exception te) {
            logger.error(te.getMessage(), te);
        }
    }
}