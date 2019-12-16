package won.bot.skeleton.action;

import org.codehaus.plexus.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import won.bot.framework.eventbot.EventListenerContext;
import won.bot.framework.eventbot.action.BaseEventBotAction;
import won.bot.framework.eventbot.event.BaseAtomAndConnectionSpecificEvent;
import won.bot.framework.eventbot.event.ConnectionSpecificEvent;
import won.bot.framework.eventbot.event.Event;
import won.bot.framework.eventbot.event.MessageEvent;
import won.bot.framework.eventbot.listener.EventListener;
import won.protocol.message.WonMessage;
import won.protocol.util.WonRdfUtils;

import java.lang.invoke.MethodHandles;

public class IncomingGenericMessageAction extends BaseEventBotAction {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    public IncomingGenericMessageAction(EventListenerContext eventListenerContext) {
        super(eventListenerContext);
    }

    @Override
    protected void doRun(Event event, EventListener eventListener) throws Exception {
        if (event instanceof BaseAtomAndConnectionSpecificEvent) {
            handleTextMessageEvent((ConnectionSpecificEvent) event);
        }
    }

    private void handleTextMessageEvent(final ConnectionSpecificEvent messageEvent) {
        if (messageEvent instanceof MessageEvent) {
            WonMessage msg = ((MessageEvent) messageEvent).getWonMessage();
            String message = extractTextMessageFromWonMessage(msg);
            System.out.println(message);
        }
    }

    private String extractTextMessageFromWonMessage(WonMessage wonMessage) {
        if (wonMessage == null) {
            return null;
        }
        String message = WonRdfUtils.MessageUtils.getTextMessage(wonMessage);
        return StringUtils.trim(message);
    }
}
