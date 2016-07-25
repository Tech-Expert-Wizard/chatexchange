package fr.tunaki.stackoverflow.chat;

import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongPredicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.ClientEndpointConfig.Builder;
import javax.websocket.ClientEndpointConfig.Configurator;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;

import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.container.jdk.client.JdkClientContainer;
import org.jsoup.Connection.Response;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import fr.tunaki.stackoverflow.chat.event.Event;
import fr.tunaki.stackoverflow.chat.event.EventType;
import fr.tunaki.stackoverflow.chat.event.Events;

public final class Room {

	private static final Logger LOGGER = LoggerFactory.getLogger(Room.class);

	private static final String SUCCESS = "ok";
	private static final Pattern TRY_AGAIN_PATTERN = Pattern.compile("You can perform this action again in (\\d+) seconds");
	private static final Pattern CURRENT_USERS_PATTERN = Pattern.compile("\\{id:\\s?(\\d+),");
	private static final int NUMBER_OF_RETRIES_ON_THROTTLE = 5;
	private static final DateTimeFormatter MESSAGE_TIME_FORMATTER = DateTimeFormatter.ofPattern("hh:mm a").withZone(ZoneOffset.UTC);
	private static final int EDIT_WINDOW_SECONDS = 115;
	private static final int WEB_SOCKET_RESTART_SECONDS = 30;

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	private final ExecutorService eventExecutor = Executors.newCachedThreadPool();

	private Session webSocketSession;
	private LocalDateTime lastWebsocketMessageDate = LocalDateTime.now();
	private Map<EventType<Object>, List<Consumer<Object>>> chatEventListeners = new HashMap<>();

	private long roomId;
	private String host;
	private String fkey;

	private HttpClient httpClient;
	private Map<String, String> cookies;

	private boolean hasLeft = false;
	
	private List<Long> pingableUserIds;
	private Set<Long> currentUserIds = new HashSet<>();

	Room(String host, long roomId, HttpClient httpClient, Map<String, String> cookies) {
		this.roomId = roomId;
		this.host = host;
		this.httpClient = httpClient;
		this.cookies = new HashMap<>(cookies);
		executeAndSchedule(() -> fkey = retrieveFKey(roomId), 1);
		executeAndSchedule(this::syncPingableUsers, 24);
		syncCurrentUsers();
		startWebSocket();
		executor.scheduleAtFixedRate(() -> {
			if (ChronoUnit.SECONDS.between(lastWebsocketMessageDate, LocalDateTime.now()) > WEB_SOCKET_RESTART_SECONDS) {
				LOGGER.debug("Rebooting the WebSocket connection after {} seconds of inactivity", WEB_SOCKET_RESTART_SECONDS);
				closeWebSocket();
				startWebSocket();
			}
		}, WEB_SOCKET_RESTART_SECONDS, WEB_SOCKET_RESTART_SECONDS, TimeUnit.SECONDS);
		addEventListener(EventType.USER_ENTERED, e -> currentUserIds.add(e.getUserId()));
		addEventListener(EventType.USER_LEFT, e -> currentUserIds.remove(e.getUserId()));
	}
	
	private void executeAndSchedule(Runnable action, int rate) {
		action.run();
		executor.scheduleAtFixedRate(action, rate, rate, TimeUnit.HOURS);
	}
	
	private JsonElement post(String url, String... data) {
		return post(NUMBER_OF_RETRIES_ON_THROTTLE, url, data);
	}
	
	private JsonElement post(int retryCount, String url, String... data) {
		Response response;
		try {
			response = httpClient.postIgnoringErrors(url, cookies, withFkey(data));
		} catch (IOException e) {
			throw new ChatOperationException(e);
		}
		String body = response.body();
		if (response.statusCode() == 200) {
			return new JsonParser().parse(body);
		}
		Matcher matcher = TRY_AGAIN_PATTERN.matcher(body);
		if (retryCount > 0 && matcher.find()) {
			long throttle = Long.parseLong(matcher.group(1));
			LOGGER.debug("Tried to POST to URL {} with data {} but was throttled, retrying in {} seconds", url, data, throttle);
			try {
				Thread.sleep(1000 * throttle);
			} catch (InterruptedException e) { }
			return post(retryCount - 1, url, data);
		} else {
			throw new ChatOperationException("The chat operation failed with the message: " + body);
		}
	}

	private String[] withFkey(String[] data) {
		String[] dataWithFKey = new String[data.length + 2];
		dataWithFKey[0] = "fkey";
		dataWithFKey[1] = fkey;
		System.arraycopy(data, 0, dataWithFKey, 2, data.length);
		return dataWithFKey;
	}

	private String retrieveFKey(long roomId) {
		try {
			Response response = httpClient.get("http://chat." + host + "/rooms/" + roomId, cookies);
			String fkey = response.parse().getElementById("fkey").val();
			LOGGER.debug("New fkey retrieved for room {}", roomId);
			return fkey;
		} catch (IOException e) {
			throw new ChatOperationException(e);
		}
	}

	private void startWebSocket() {
		String websocketUrl = post("http://chat." + host + "/ws-auth", "roomid", String.valueOf(roomId)).getAsJsonObject().get("url").getAsString();
		String time = post("http://chat." + host + "/chats/" + roomId + "/events").getAsJsonObject().get("time").getAsString();
		ClientManager client = ClientManager.createClient(JdkClientContainer.class.getName());
		Builder configBuilder = ClientEndpointConfig.Builder.create();
		configBuilder.configurator(new Configurator() {
			@Override
			public void beforeRequest(Map<String, List<String>> headers) {
				headers.put("Origin", Arrays.asList("http://chat." + host));
			}
		});
		websocketUrl += "?l=" + time;
		LOGGER.debug("Connecting to chat websocket " + websocketUrl);
		try {
			webSocketSession = client.connectToServer(new Endpoint() {
				@Override
				public void onOpen(Session session, EndpointConfig config) {
					session.addMessageHandler(String.class, Room.this::handleChatEvent);
				}
				@Override
				public void onError(Session session, Throwable thr) {
					LOGGER.error("An error occured during the processing of a message", thr);
				}
			}, configBuilder.build(), new URI(websocketUrl));
		} catch (DeploymentException | URISyntaxException | IOException e) {
			throw new ChatOperationException("Cannot connect to chat websocket", e);
		}
	}
	
	private void closeWebSocket() {
		try {
			webSocketSession.close();
		} catch (IOException e) {
			LOGGER.error("Error while closing the WebSocket", e);
		}
	}

	private void handleChatEvent(String json) {
		LOGGER.debug("Received message: " + json);
		lastWebsocketMessageDate = LocalDateTime.now();
		JsonObject jsonObject = new JsonParser().parse(json).getAsJsonObject();
		jsonObject.entrySet().stream().filter(e -> e.getKey().equals("r" + roomId)).map(Map.Entry::getValue).map(JsonElement::getAsJsonObject).map(o -> o.get("e")).filter(Objects::nonNull).map(JsonElement::getAsJsonArray).findFirst().ifPresent(events -> {
			for (Event event : Events.fromJsonData(events, this)) {
				for (Consumer<Object> listener : chatEventListeners.getOrDefault(EventType.fromEvent(event), Collections.emptyList())) {
					eventExecutor.submit(() -> listener.accept(event));
				}
			}
		});
	}

	/**
	 * Adds a listener for the given event. Valid events are defined as constants of the {@link EventType} class.
	 * <p>All listeners bound to a specific event will be called when the corresponding event is raised.
	 * @param event Event to listen to.
	 * @param listener Listener to add to this event.
	 */
	public <T> void addEventListener(EventType<T> event, Consumer<T> listener) {
		@SuppressWarnings("unchecked") EventType<Object> eventCast = (EventType<Object>) event;
		@SuppressWarnings("unchecked") Consumer<Object> listenerCast = (Consumer<Object>) listener;
		chatEventListeners.computeIfAbsent(eventCast, e -> new ArrayList<>()).add(listenerCast);
	}
	
	private <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
		return CompletableFuture.supplyAsync(supplier, executor).whenComplete((res, thr) -> {
			if (res != null) LOGGER.trace("Task completed successfully with result: " + res);
			if (thr != null) LOGGER.error("Couldn't execute task", thr);
		});
	}

	/**
	 * Sends the given message asynchronously.
	 * @param message Content of the message to send.
	 * @return A future holding the id of the sent message.
	 */
	public CompletableFuture<Long> send(String message) {
		LOGGER.info("Task added - sending message '{}' to room {}.", message, roomId);
		return supplyAsync(() -> {
			JsonElement element = post("http://chat." + host + "/chats/" + roomId + "/messages/new", "text", message);
			LOGGER.debug("Message '{}' sent to room {}, raw result: {}", message, roomId, element);
			return element.getAsJsonObject().get("id").getAsLong();
		});
	}

	/**
	 * Sends a reply message to the given message id.
	 * @param messageId Id of the message to reply to.
	 * @param message Message consisting of the reply.
	 * @return A future holding the id of the newly sent message.
	 */
	public CompletableFuture<Long> replyTo(long messageId, String message) {
		return send(":" + messageId + " " + message);
	}

	/**
	 * Edits asynchronously the message having the given id with the new given content.
	 * @param messageId Id of the message to edit.
	 * @param message New content of the message.
	 * @return A future holding the id of the edited message (which is the same as the given message id).
	 */
	public CompletableFuture<Long> edit(long messageId, String message) {
		LOGGER.info("Task added - editing message {} in room {}.", messageId, roomId);
		return supplyAsync(() -> {
			String result = post("http://chat." + host + "/messages/" + messageId, "text", message).getAsString();
			LOGGER.debug("Message {} edited to '{}' in room {}, raw result: {}", messageId, message, roomId, result);
			if (!SUCCESS.equals(result)) {
				throw new ChatOperationException("Cannot edit message " + messageId + ". Reason: " + result);
			}
			return messageId;
		});
	}
	
	/**
	 * Returns whether this message can be edited as of now. This doesn't guarantee that a subsequent call to {@link #edit(long, String)}
	 * will be successful, because the time window allowed for the edit could have been passed by then. However, if a call to
	 * {@link #edit(long, String)} is made right after this method returns <code>true</code> then it is very likely to succeed
	 * (i.e. not fail because the edit window has elapsed; it can still fail for other reasons).
	 * <p>A message can be edited if it has been posted less than {@value #EDIT_WINDOW_SECONDS} seconds ago.
	 * @param messageId Id of the message.
	 * @return <code>true</code> if the given message can be edited right now, <code>false</code> otherwise.
	 */
	public boolean isEditable(long messageId) {
		try {
			Document documentHistory = httpClient.get("http://chat." + host + "/messages/" + messageId + "/history", cookies, "fkey", fkey).parse();
			LocalTime time = LocalTime.parse(documentHistory.getElementsByClass("timestamp").last().html(), MESSAGE_TIME_FORMATTER);
			return ChronoUnit.SECONDS.between(time, LocalTime.now(ZoneOffset.UTC)) < EDIT_WINDOW_SECONDS;
		} catch (IOException e) {
			throw new ChatOperationException(e);
		}
	}

	/**
	 * Deletes asynchronously the message having the given id.
	 * @param messageId Id of the message to delete.
	 * @return A future holding no value.
	 */
	public CompletableFuture<Void> delete(long messageId) {
		LOGGER.info("Task added - deleting message {} in room {}.", messageId, roomId);
		return supplyAsync(() -> {
			String result = post("http://chat." + host + "/messages/" + messageId + "/delete").getAsString();
			LOGGER.debug("Message {} deleted in room {}, raw result: {}", messageId, roomId, result);
			if (!SUCCESS.equals(result)) {
				throw new ChatOperationException("Cannot delete message " + messageId + ". Reason: " + result);
			}
			return null;
		});
	}

	public CompletableFuture<Void> toggleStar(long messageId) {
		LOGGER.info("Task added - starring/unstarring message {} in room {}.", messageId, roomId);
		return supplyAsync(() -> {
			String result = post("http://chat." + host + "/messages/" + messageId + "/star").getAsString();
			LOGGER.debug("Message {} starred/unstarred in room {}, raw result: {}", messageId, roomId, result);
			if (!SUCCESS.equals(result)) {
				throw new ChatOperationException("Cannot star/unstar message " + messageId + ". Reason: " + result);
			}
			return null;
		});
	}

	public CompletableFuture<Void> togglePin(long messageId) {
		LOGGER.info("Task added - pining/unpining message {} in room {}.", messageId, roomId);
		return supplyAsync(() -> {
			String result = post("http://chat." + host + "/messages/" + messageId + "/owner-star").getAsString();
			LOGGER.debug("Message {} pined/unpined in room {}, raw result: {}", messageId, roomId, result);
			if (!SUCCESS.equals(result)) {
				throw new ChatOperationException("Cannot pin/unpin message " + messageId + ". Reason: " + result);
			}
			return null;
		});
	}

	/**
	 * Causes the current logged user to leave the room.
	 * <p>Calling this method multiple times has no effect.
	 */
	public void leave() {
		if (hasLeft) return;
		LOGGER.debug("Leaving room {} on {}", roomId, host);
		post("http://chat." + host + "/chats/leave/" + roomId, "quiet", "true");
		close();
		hasLeft = true;
	}
	
	/**
	 * Retrieves the {@link Message} having the given id.
	 * @param messageId Id of the message to fetch.
	 * @return Message with the given id.
	 */
	public Message getMessage(long messageId) {
		try {
			String plainContent = httpClient.get("http://chat." + host + "/message/" + messageId, cookies, "fkey", fkey, "plain", "true").body();
			String content = httpClient.get("http://chat." + host + "/message/" + messageId, cookies, "fkey", fkey, "plain", "false").body();
			Document documentHistory = httpClient.get("http://chat." + host + "/messages/" + messageId + "/history", cookies, "fkey", fkey).parse();
			User user = getUser(Long.parseLong(documentHistory.select(".username > a").first().attr("href").split("/")[2]));
			boolean deleted = documentHistory.select(".message .content").stream().anyMatch(e -> e.getElementsByTag("b").html().equals("deleted"));
			return new Message(messageId, user, plainContent, content, deleted);
		} catch (HttpStatusException e) {
			if (e.getStatusCode() == 404) {
				LOGGER.info("Tried to view deleted message " + messageId);
				// non-RO cannot see deleted message of another user: so if 404, it means message is deleted
				return new Message(messageId, null, null, null, true);
			}
			throw new ChatOperationException(e);
		} catch (IOException e) {
			throw new ChatOperationException(e);
		}
	}
	
	/**
	 * Returns the list of all the pingable users of this room.
	 * <p>This consists of all the users that have been in the room at least once for the past 14 days.
	 * @return List of pingable users of this room.
	 */
	public List<User> getPingableUsers() {
		return getUsers(pingableUserIds, currentUserIds::contains);
	}
	
	private void syncPingableUsers() {
		String json;
		try {
			json = httpClient.get("http://chat." + host + "/rooms/pingable/" + roomId, cookies).body();
		} catch (IOException e) {
			throw new ChatOperationException(e);
		}
		JsonArray array = new JsonParser().parse(json).getAsJsonArray();
		pingableUserIds = StreamSupport.stream(array.spliterator(), false).map(e -> e.getAsJsonArray().get(0).getAsLong()).collect(Collectors.toList());
	}
	
	/**
	 * Returns the list of all the current users of this room.
	 * <p>This consists of all the users that are present, at the moment of this call, in the room.
	 * @return List of current users of this room.
	 */
	public List<User> getCurrentUsers() {
		return getUsers(currentUserIds, id -> true);
	}
	
	private void syncCurrentUsers() {
		Document document;
		try {
			document = httpClient.get("http://chat." + host + "/rooms/" + roomId, cookies).parse();
		} catch (IOException e) {
			throw new ChatOperationException(e);
		}
		String html = document.getElementsByTag("script").get(3).html();
		Matcher matcher = CURRENT_USERS_PATTERN.matcher(html);
		currentUserIds.clear();
		while (matcher.find()) {
			currentUserIds.add(Long.valueOf(matcher.group(1)));
		}
	}
	
	/**
	 * Retrieves the {@link User} having the given id.
	 * @param messageId Id of the user to fetch.
	 * @return User with the given id.
	 */
	public User getUser(long userId) {
		return getUsers(Arrays.asList(userId), currentUserIds::contains).get(0);
	}
	
	private List<User> getUsers(Iterable<Long> userIds, LongPredicate inRoom) {
		String ids = StreamSupport.stream(userIds.spliterator(), false).map(Object::toString).collect(Collectors.joining(","));
		return StreamSupport.stream(post("http://chat." + host + "/user/info", "ids", ids, "roomId", String.valueOf(roomId)).getAsJsonObject().get("users").getAsJsonArray().spliterator(), false).map(JsonElement::getAsJsonObject).map(object -> {
			long id = object.get("id").getAsLong();
			String userName = object.get("name").getAsString();
			int reputation = object.get("reputation").getAsInt();
			boolean moderator = object.get("is_moderator").isJsonNull() ? false : object.get("is_moderator").getAsBoolean();
			boolean owner = object.get("is_owner").isJsonNull() ? false : object.get("is_owner").getAsBoolean();
			Instant lastSeen = object.get("last_seen").isJsonNull() ? null : Instant.ofEpochSecond(object.get("last_seen").getAsLong());
			Instant lastMessage = object.get("last_post").isJsonNull() ? null : Instant.ofEpochSecond(object.get("last_post").getAsLong());
			return new User(id, userName, reputation, moderator, owner, lastSeen, lastMessage, inRoom.test(id));
		}).collect(Collectors.toList());
	}

	/**
	 * Returns the id of this room. This id needs to be combined with the host
	 * of this room to reference uniquely this room, as there can be rooms with
	 * the same id across multiple hosts.
	 * @return Id of this room.
	 */
	public long getRoomId() {
		return roomId;
	}
	
	/**
	 * Returns the thumbs for this chat room. This includes various informations such as: name, description...
	 * <p>Refer to {@link RoomThumbs} for a description of all the fields.
	 * @return Thumbs for this chat room
	 */
	public RoomThumbs getThumbs() {
		String json;
		try {
			json = httpClient.get("http://chat." + host + "/rooms/thumbs/" + roomId, cookies).body();
		} catch (IOException e) {
			throw new ChatOperationException(e);
		}
		JsonObject obj = new JsonParser().parse(json).getAsJsonObject();
		List<String> tags = Jsoup.parse(obj.get("tags").getAsString()).getElementsByTag("a").stream().map(Element::html).collect(Collectors.toList());
		return new RoomThumbs(obj.get("id").getAsLong(), obj.get("name").getAsString(), obj.get("description").getAsString(), obj.get("isFavorite").getAsBoolean(), tags);
	}

	/**
	 * Returns the host of this room. Values are <code>stackoverflow.com</code>,
	 * <code>stackexchange.com</code> and <code>meta.stackexchange.com</code>
	 * 
	 * @return Host of this room.
	 */
	public String getHost() {
		return host;
	}

	void close() {
		executor.shutdown();
		eventExecutor.shutdown();
		closeWebSocket();
	}

}
