package com.github.wolfie.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import elemental.json.JsonArray;
import elemental.json.JsonException;
import elemental.json.JsonObject;
import elemental.json.JsonNull;

import com.github.wolfie.history.HistoryExtension.ErrorEvent.Type;
import com.vaadin.annotations.JavaScript;
import com.vaadin.navigator.NavigationStateManager;
import com.vaadin.navigator.Navigator;
import com.vaadin.navigator.ViewDisplay;
import com.vaadin.server.AbstractClientConnector;
import com.vaadin.server.AbstractJavaScriptExtension;
import com.vaadin.server.Page;
import com.vaadin.server.VaadinServlet;
import com.vaadin.ui.ComponentContainer;
import com.vaadin.ui.JavaScriptFunction;
import com.vaadin.ui.SingleComponentContainer;
import com.vaadin.ui.UI;

/**
 * An extension that allows server-side control over the HTML5
 * <code>history</code> JavaScript object, and also allows listening to the
 * {@link PopStateEvent}.
 *
 * @author Henrik Paul
 */
@SuppressWarnings("serial")
@JavaScript("historyextension.js")
public class HistoryExtension extends AbstractJavaScriptExtension {
    
    protected final static ObjectMapper defaultMapper = new ObjectMapper();

    private final class NavManager implements NavigationStateManager,
            PopStateListener {

        private final Map emptyStateObject = null;
        private Navigator navigator;
        private String state = null;
        private final String urlRoot;
        private String query;

        public NavManager(final String urlRoot) {
            this.urlRoot = urlRoot;
            addPopStateListener(this);
        }

        @Override
        public String getState() {
            if (state == null) {
                state = parseStateFrom(navigator.getUI());
            }
            return state;
        }

        @Override
        public void setState(final String state) {
            this.state = state;
            final String pushStateUrl = urlRoot + "/" + state
                    + (query != null ? "?" + query : "");

            pushState(emptyStateObject, pushStateUrl);
        }

        @Override
        public void setNavigator(final Navigator navigator) {
            this.navigator = navigator;
        }

        @Override
        public void popState(final PopStateEvent event) {
            state = parseStateFrom(event.getAddress());
            navigator.navigateTo(state);
        }

        private String parseStateFrom(final UI ui) {
            if (ui != null) {
                final Page page = ui.getPage();
                if (page != null) {
                    return parseStateFrom(page.getLocation());
                } else {
                    Logger.getLogger(getClass().getName()).warning(
                            "Could not parse a proper state string: "
                            + "Page was null");
                }
            } else {
                Logger.getLogger(getClass().getName()).warning(
                        "Could not parse a proper state string: "
                        + "UI was null");
            }
            return "";
        }

        private String parseStateFrom(final URI uri) {
            final String path = uri.getPath();
            if (!path.startsWith(urlRoot)) {
                Logger.getLogger(getClass().getName()).warning(
                        "URI " + uri + " doesn't start with the urlRoot "
                        + urlRoot);
                return "";
            }

            String parsedState = path.substring(urlRoot.length());
            if (parsedState.startsWith("/")) {
                parsedState = parsedState.substring(1);
            }
            query = uri.getQuery();
            return parsedState;
        }
    }

    /**
     * An event class that carries application state data for the currently
     * selected browser history entry
     */
    public class PopStateEvent {

        private Map<String, String> map = null;
        private final JsonObject json;
        private final String stringAddress;
        private URI address;

        private PopStateEvent(final JsonObject json, final String address) {
            this.json = json;
            this.stringAddress = address;
        }

        /**
         * Returns the state data as an {@link JsonObject}. Never
         * <code>null</code>.
         * 
         * @return The state object as JsonObject
         */
        public JsonObject getStateAsJson() {
            return json;
        }

        /**
         * Returns the state data as an <strong>unmodifiable</strong>
         * {@link Map Map}. Never <code>null</code>.
         * 
         * @return the state object
         */
        public Map<String, String> getStateAsMap() {
            if (map == null) {
                final LinkedHashMap<String, String> tempMap = new LinkedHashMap<String, String>();
                final String[] names = json.keys();
                if (names != null) {
                    for (final String key : names) {
                        try {
                            tempMap.put(key, json.getString(key));
                        } catch (final JsonException e) {
                            throw new RuntimeException("org.json.JSONObject "
                                    + "seems to have a bug, as it's "
                                    + "returning keys on objects "
                                    + "that don't exist.", e);
                        }
                    }
                }
                map = Collections.unmodifiableMap(tempMap);
            }
            return map;
        }

        /**
         * @return the {@link HistoryExtension} instance from which this event
         * was fired.
         */
        public HistoryExtension getSource() {
            return HistoryExtension.this;
        }

        /**
         * Returns the address to which it was changed, as it reads in the
         * browser location bar.
         * <p>
         * <em>Note:</em>
         * <code>{@link UI#getPage() getPage()}.getLocation()</code> is not
         * properly updated on a state push/replace/pop event, and therefore
         * will probably not return the correct value (at least in Vaadin
         * 7.1.10).
         * 
         * @return the address
         */
        public URI getAddress() {
            if (address == null) {
                try {
                    address = new URI(stringAddress);
                } catch (final URISyntaxException e) {
                    throw new RuntimeException(e);
                }
            }
            return address;
        }
    }

    /**
     * An interface that allows external code to listen to browser history
     * changes.
     *
     * @see HistoryExtension#addPopStateListener(PopStateListener)
     * @see HistoryExtension#removePopStateListener(PopStateListener)
     */
    public static interface PopStateListener {

        /**
         * A state was popped off the browser's history stack
         *
         * @param event The event object describing the application state. Will
         * be <code>null</code> if no state object was explicitly given for a
         * particular history entry.
         * @see HistoryExtension#pushState(JsonObject, String)
         * @see HistoryExtension#pushState(Map, String)
         */
        void popState(PopStateEvent event);
    }

    /**
     * An event that carries information about an error that occurred on the
     * client side.
     * <p>
     * <em>Note:</em> if an ErrorEvent is not {@link #cancel() cancelled},
     * {@link HistoryExtension} will raise a runtime exception.
     *
     * @see #cancel()
     * @see HistoryExtension#addErrorListener(ErrorListener)
     */
    public static class ErrorEvent {

        /**
         * An enum describing the type of error that occurred
         */
        public enum Type {
            /**
             * HTML 5 history functionality is unsupported by the user's
             * browser.
             */
            UNSUPPORTED,
            /**
             * A client-side error occurred when trying to execute the command.
             */
            METHOD_INVOKE
        }

        private final Type type;
        private final String name;
        private final String message;
        private final String stringAddress;
        private boolean cancelled;
        private URI address;

        private ErrorEvent(final Type type, final String name,
                final String message, final String address) {
            this.type = type;
            this.name = name;
            this.message = message;
            this.stringAddress = address;
        }

        /**
         * @return the type of error that occurred
         */
        public Type getType() {
            return type;
        }

        /**
         * @return The name of the error that occurred, as given by the browser.
         */
        public String getErrorName() {
            return name;
        }

        /**
         * @return The descriptive message of that error, as given by the browser.
         */
        public String getMessage() {
            return message;
        }

        /**
         * Returns the address as it reads in the browser location bar when the
         * error occurred.
         * <p>
         * <em>Note:</em>
         * <code>{@link UI#getPage() getPage()}.getLocation()</code> is not
         * properly updated on a state push/replace/pop event, and therefore
         * will probably not return the correct value (at least in Vaadin
         * 7.1.10).
         * 
         * @return the address
         */
        public URI getAddress() {
            if (address == null) {
                try {
                    address = new URI(stringAddress);
                } catch (final URISyntaxException e) {
                    throw new RuntimeException(e);
                }
            }
            return address;
        }

        /**
         * Cancels the error message.
         * <p>
         * If a fired {@link ErrorEvent} is not cancelled by a listener,
         * {@link HistoryExtension} will throw a {@link RuntimeException} that
         * describes what happened.
         * <p>
         * Cancelling an {@link ErrorEvent} lets the {@link HistoryExtension}
         * know that the error has been dealt with, and no additional noise
         * needs to be raised regarding it.
         * <p>
         * All {@link ErrorListener ErrorListeners} still are called, even if an
         * event gets called.
         *
         * @see #isCancelled()
         */
        public void cancel() {
            cancelled = true;
        }

        /**
         * Checks whether this error has been cancelled or not.
         *
         * @return true if cancelled
         * @see #cancel()
         */
        public boolean isCancelled() {
            return cancelled;
        }
    }

    /**
     * An listener interface that allows the server code to listen to
     * asynchronously happened client-side errors.
     * <p>
     * The {@link ErrorListener} should call {@link ErrorEvent#cancel()} on an
     * event that has explicitly been dealt with, otherwise
     * {@link HistoryExtension} will throw a {@link RuntimeException}.
     *
     * @see HistoryExtension#addErrorListener(ErrorListener)
     * @see HistoryExtension#removeErrorListener(ErrorListener)
     */
    public static interface ErrorListener {

        void onError(ErrorEvent event);
    }

    private final List<PopStateListener> popListeners = new ArrayList<PopStateListener>();
    private final List<ErrorListener> errorListeners = new ArrayList<ErrorListener>();

    /**
     * A flag that is set <em>asynchronously</em>. It denotes whether the
     * browser supports HTML 5 history manipulation or not.
     *
     * @see ErrorEvent.Type#UNSUPPORTED
     */
    private boolean unsupported = false;

    private String urlRoot;

    /**
     * A convenience method to extend a UI with a properly configured
     * {@link HistoryExtension}.
     * @param ui the UI for which the HistoryExtension should be connected
     * @param listener a listener to be set for HistoryExtension
     * @return the HistoryExtension
     */
    public static HistoryExtension extend(final UI ui,
            final PopStateListener listener) {
        final HistoryExtension extension = new HistoryExtension();
        extension.addPopStateListener(listener);
        extension.extend(ui);
        return extension;
    }

    /**
     * A convenience method to extend a UI with a properly configured
     * {@link HistoryExtension}.
     * @param ui the UI for the HistoryExtension should be connected to 
     * @param popStateListener listener for HistoryExtension
     * @param errorListener listener for HistoryExtension
     * @return the configured HistoryExtension
     */
    public static HistoryExtension extend(final UI ui,
            final PopStateListener popStateListener,
            final ErrorListener errorListener) {
        final HistoryExtension extension = extend(ui, popStateListener);
        extension.addErrorListener(errorListener);
        return extension;
    }

    /**
     * Creates a new {@link HistoryExtension}
     */
    public HistoryExtension() {
        addFunction("popstate", new JavaScriptFunction() {
            @Override
            public void call(final JsonArray arguments) throws JsonException {
                if (arguments.length() > 0) {
                    try {
                        final String address = arguments.getString(1);
                        JsonObject state;
                        if (arguments.length() > 0 && !(arguments.get(0) instanceof JsonNull) && arguments.get(0) != null) {
                            // state not always object, I assuem this is bug, but on the other hand there is usually no need for state in Vaadin app 
                            try {
                                state = arguments.getObject(0);
                            } catch (Exception e) {
                                state = null;
                            }
                        } else {
                            state = null;
                        }
                        fireListeners(state, address);
                    } catch (final JsonException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        });

        addFunction("error", new JavaScriptFunction() {
            @Override
            public void call(final JsonArray arguments) throws JsonException {
                final int errorType = (int) arguments.getNumber(0);
                final ErrorEvent.Type type = ErrorEvent.Type.values()[errorType];
                final String name = arguments.getString(1);
                final String message = arguments.getString(2);
                final String address = arguments.getString(3);

                final ErrorEvent event = new ErrorEvent(type, name, message,
                        address);
                fireError(event);
            }
        });
    }

    /**
     * Extend a {@link UI} with this {@link HistoryExtension}
     * @param ui The UI to be extended
     */
    public void extend(final UI ui) {
        final AbstractClientConnector acc = ui;
        super.extend(acc);
    }

    /**
     * Tells the browser to go back one step in its history stack.
     */
    public void back() {
        callFunction("back");
    }

    /**
     * Tells the browser to go forward one step in its history stack.
     */
    public void forward() {
        callFunction("forward");
    }

    /**
     * Tells the browser to go forward or backwards a certain amount of steps in
     * its history stack.
     * <p>
     * Negative values go backwards, positive values go forwards.
     * @param steps the amount of steps browsers should take in the history
     */
    @SuppressWarnings("boxing")
    public void go(final int steps) {
        callFunction("go", steps);
    }

    /**
     * Pushes a state object, represented by a {@link Map Map},
     * in the browser's history stack.
     * <p>
     * <em>Note:</em> The state should represent the state of the application as
     * it will become, once the URL has changed. So, for example, a select
     * widget has the value "foo", and the user changes that selection to the
     * value "bar". In this case, we want the state object to reflect that value
     * "bar", and perhaps change the url to "/bar".
     * <p>
     * In that case, we would write code similar to the following:
     *
     * <pre>
     * Map&lt;String, String&gt; stateMap = new HashMap&lt;String, String&gt;();
     * stateMap.put("userChoice", "bar");
     * extension.pushState(stateMap, "/bar");
     * </pre>
     *
     * @param nextStateMap The state representing the <strong>upcoming</strong>
     * application state
     * @param nextUrl A URI string of what will be displayed in the browser's
     * location bar. Or <code>null</code> if the current URL should be used
     * instead
     * @see PopStateListener
     * @see PopStateEvent#getStateAsMap()
     */
    public void pushState(final Map<String, String> nextStateMap,
            final String nextUrl) {
        callFunction("pushState", toJson(nextStateMap), nextUrl);
    }
    
    protected String toJson(Map m) {
        try {
            return defaultMapper.writeValueAsString(m);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Pushes a state object, represented by a {@link JsonObject}, in the
     * browser's history stack.
     * <p>
     * <em>Note:</em> The state should represent the state of the application as
     * it will become, once the URL has changed. So, for example, a select
     * widget has the value "foo", and the user changes that selection to the
     * value "bar". In this case, we want the state object to reflect that value
     * "bar", and perhaps change the url to "/bar".
     * <p>
     * In that case, we would write code similar to the following:
     *
     * <pre>
     * JSONObject stateJson = new JSONObject();
     * stateJson.put("userChoice", "bar");
     * extension.pushState(stateJson, "/bar");
     * </pre>
     *
     * @param nextStateJson The state representing the <strong>upcoming</strong>
     * application state
     * @param nextUrl A URI string of what will be displayed in the browser's
     * location bar. Or <code>null</code> if the current URL should be used
     * instead
     * @see PopStateListener
     * @see PopStateEvent#getStateAsMap()
     */
    public void pushState(final JsonObject nextStateJson, final String nextUrl) {
        callFunction("pushState", nextStateJson, nextUrl);
    }

    /**
     * Similar to {@link #pushState(Map, String)}, but instead of adding a new
     * history entry in the browser, it replaces the current one.
     * <p>
     * Works perfectly for tracking runtime progress (e.g. scrolling down a
     * page, or displaying the progress while watching a movie).
     * <p>
     * It usually is a good idea to initialize your application with a call to
     * this method, so that your application gets a "proper" state object when
     * the user presses back to this initial state.
     *
     * @param newStateMap The state representing the <strong>upcoming</strong>
     * application state
     * @param newUrl A URI string of what will be displayed in the browser's
     * location bar. Or <code>null</code> if the current URL should be used
     * instead
     * @see PopStateListener
     * @see PopStateEvent#getStateAsMap()
     */
    public void replaceState(final Map<String, String> newStateMap,
            final String newUrl) {
        callFunction("replaceState", toJson(newStateMap), newUrl);
    }

    /**
     * Similar to {@link #pushState(JsonObject, String)}, but instead of adding
     * a new history entry in the browser, it replaces the current one.
     * <p>
     * Works perfectly for tracking runtime progress (e.g. scrolling down a
     * page, or displaying the progress while watching a movie).
     * <p>
     * It usually is a good idea to initialize your application with a call to
     * this method, so that your application gets a "proper" state object when
     * the user presses back to this initial state.
     *
     * @param newStateJson The state representing the <strong>upcoming</strong>
     * application state
     * @param newUrl A URI string of what will be displayed in the browser's
     * location bar. Or <code>null</code> if the current URL should be used
     * instead
     * @see PopStateListener
     * @see PopStateEvent#getStateAsJson()
     */
    public void replaceState(final JsonObject newStateJson, final String newUrl) {
        callFunction("replaceState", newStateJson, newUrl);
    }

    /**
     * Adds a {@link PopStateListener}
     *
     * @param listener the listener to be added
     * @throws IllegalArgumentException if <code>listener</code> is
     * <code>null</code>
     */
    public void addPopStateListener(final PopStateListener listener)
            throws IllegalArgumentException {
        if (listener == null) {
            throw new IllegalArgumentException("listener may not be null");
        }
        popListeners.add(listener);
    }

    /**
     * Removes a {@link PopStateListener}
     *
     * @param listener the listener to be removed
     * @return <code>true</code> if the listener was successfully found and
     * removed, otherwise <code>false</code>
     */
    public boolean removePopStateListener(final PopStateListener listener) {
        return popListeners.remove(listener);
    }

    /**
     * Adds an {@link ErrorListener}
     *
     * @param listener the listener to be added
     * @throws IllegalArgumentException if <code>listener</code> is
     * <code>null</code>
     */
    public void addErrorListener(final ErrorListener listener)
            throws IllegalArgumentException {
        if (listener == null) {
            throw new IllegalArgumentException("listener may not be null");
        }
        errorListeners.add(listener);
    }

    /**
     * Removes an {@link ErrorListener}
     *
     * @param listener the listener to be removed
     * @return <code>true</code> if the listener was successfully found and
     * removed, otherwise <code>false</code>
     */
    public boolean removeErrorListener(final ErrorListener listener) {
        return errorListeners.remove(listener);
    }

    private void fireListeners(final JsonObject state, final String address) {
        final PopStateEvent event = new PopStateEvent(state, address);
        for (final PopStateListener listener : popListeners) {
            listener.popState(event);
        }
    }

    private void fireError(final ErrorEvent e) {
        for (final ErrorListener listener : errorListeners) {
            listener.onError(e);
        }

        if (!e.isCancelled()) {
            throw new RuntimeException(e.getErrorName() + ": " + e.getMessage());
        }

        if (e.getType() == Type.UNSUPPORTED) {
            unsupported = true;
        }
    }

    @Override
    protected void callFunction(final String name, final Object... arguments) {
        /*
         * This method is overridden to stop all client-side calls if the API is
         * not supported.
         * 
         * Otherwise we'd get a lot of unnecessary function calls that will
         * simply end up failing.
         */

        if (!unsupported) {
            super.callFunction(name, arguments);
        } else {
            Logger.getLogger(getClass().getName()).warning(
                    "PushState is unsupported by the client "
                    + "browser. Ignoring RPC call for "
                    + getClass().getSimpleName() + "." + name);
        }
    }

    public NavigationStateManager createNavigationStateManager(
            final String urlRoot) {
        return new NavManager(urlRoot);
    }

    /**
     * A helper method to configure a "pushState" enabled Navigator for given UI
     * and ViewDisplay.
     *
     * @param ui the UI for which the Navigator should be configured
     * @param display a ViewDisplay to be used to hold the views
     */
    public static void configurePushStateEnabledNavigator(UI ui, ViewDisplay display) {
        HistoryExtension history = new HistoryExtension();
        history.extend(ui);

        String contextPath = VaadinServlet.getCurrent().getServletContext()
                .getContextPath();

        NavigationStateManager pushStateManager = history.createNavigationStateManager(contextPath);
        new Navigator(ui, pushStateManager, display);
    }

    /**
     * A helper method to configure a "pushState" enabled Navigator for given UI
     * and SingleComponentContainer to be used as view display.
     *
     * @param ui the UI for which the Navigator should be configured
     * @param display a SingleComponentContainer to be used to hold the views
     */
    public static void configurePushStateEnabledNavigator(UI ui, SingleComponentContainer display) {
        HistoryExtension history = new HistoryExtension();
        history.extend(ui);

        String contextPath = VaadinServlet.getCurrent().getServletContext()
                .getContextPath();

        final NavigationStateManager pushStateManager = history.createNavigationStateManager(contextPath);

        Navigator navigator = new Navigator(ui, display) {
            @Override
            protected void init(UI ui, NavigationStateManager stateManager, ViewDisplay display) {
                super.init(ui, pushStateManager, display);
            }

        };

    }

    /**
     * A helper method to configure a "pushState" enabled Navigator for given UI
     * and ComponentContainer to be used as view display.
     *
     * @param ui the UI for which the Navigator should be configured
     * @param display a ComponentContainer to be used to hold the views
     */
    public static void configurePushStateEnabledNavigator(UI ui, ComponentContainer display) {
        HistoryExtension history = new HistoryExtension();
        history.extend(ui);

        String contextPath = VaadinServlet.getCurrent().getServletContext()
                .getContextPath();

        final NavigationStateManager pushStateManager = history.createNavigationStateManager(contextPath);

        Navigator navigator = new Navigator(ui, display) {
            @Override
            protected void init(UI ui, NavigationStateManager stateManager, ViewDisplay display) {
                super.init(ui, pushStateManager, display);
            }

        };

    }

}
