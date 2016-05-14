package fr.tunaki.stackoverflow.chat.event;

import java.util.function.Function;

import com.google.gson.JsonElement;

/**
 * This class represents a chat event.
 * <p>An event is an action that happened in a room and contains a message. This class is final and cannot be instantiated, only
 * the pre-defined constants must be used.
 * @author Tunaki
 * @param <T> Type of the message for this event.
 */
public final class Event<T> {
	
	/**
	 * Event raised when a message is posted in a room.
	 * This event only targets messages posted by users, and not system-generated messages (like adding a feed).
	 * <p>All messages posted by users raise this event, even replies or mentions.
	 */
	public static final Event<MessagePostedEvent> MESSAGE_POSTED = new Event<>(MessagePostedEvent::new);
	
	/**
	 * Event raised when a message is edited in a room.
	 * <p>All messages posted by users and then edited raise this event, even replies or mentions.
	 */
	public static final Event<MessageEditedEvent> MESSAGE_EDITED = new Event<>(MessageEditedEvent::new);
	
	/**
	 * Event raised when a reply is posted to the current logged-in user. A reply is a message targeting a specific other message.
	 * In chat, this is the <code>:{messageId}</code> feature.
	 * <p>When this event is raised, a corresponding {@link #MESSAGE_POSTED} or {@link #MESSAGE_EDITED} will be raised.
	 * This event is still useful to listen specifically to replies of one's messages instead of all posted / edited messages.
	 */
	public static final Event<MessageReplyEvent> MESSAGE_REPLY = new Event<>(MessageReplyEvent::new);
	
	/**
	 * Event raised when a mention of the current logged-in user is made. A mention is a message pinging a user without replying
	 * to a specific message. In chat, this is the <code>@{username}</code> feature.
	 * <p>When this event is raised, a corresponding {@link #MESSAGE_POSTED} or {@link #MESSAGE_EDITED} will be raised.
	 * This event is still useful to listen specifically to mentions of the logged-in user instead of all posted / edited messages.
	 */
	public static final Event<UserMentionedEvent> USER_MENTIONED = new Event<>(UserMentionedEvent::new);
	
	private final Function<JsonElement, T> function;
	
	private T message;
	
	private Event(Function<JsonElement, T> function) {
		this.function = function;
	}
	
	Event<T> withData(JsonElement jsonElement) {
		message = function.apply(jsonElement);
		return this;
	}
	
	/**
	 * Returns the message of this event.
	 * @return Message of this event.
	 */
	public T message() {
		return message;
	}
	
}
