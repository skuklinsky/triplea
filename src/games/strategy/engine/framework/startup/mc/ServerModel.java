package games.strategy.engine.framework.startup.mc;

import java.awt.Component;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

import javax.swing.JOptionPane;

import games.strategy.engine.chat.Chat;
import games.strategy.engine.chat.ChatController;
import games.strategy.engine.chat.ChatPanel;
import games.strategy.engine.chat.HeadlessChat;
import games.strategy.engine.chat.IChatPanel;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.PlayerID;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.engine.data.properties.IEditableProperty;
import games.strategy.engine.framework.GameDataManager;
import games.strategy.engine.framework.GameObjectStreamFactory;
import games.strategy.engine.framework.GameRunner2;
import games.strategy.engine.framework.headlessGameServer.HeadlessGameServer;
import games.strategy.engine.framework.message.PlayerListing;
import games.strategy.engine.framework.startup.launcher.ILauncher;
import games.strategy.engine.framework.startup.launcher.ServerLauncher;
import games.strategy.engine.framework.startup.login.ClientLoginValidator;
import games.strategy.engine.framework.startup.ui.ServerOptions;
import games.strategy.engine.framework.ui.SaveGameFileChooser;
import games.strategy.engine.lobby.server.NullModeratorController;
import games.strategy.engine.message.ChannelMessenger;
import games.strategy.engine.message.IChannelMessenger;
import games.strategy.engine.message.IRemoteMessenger;
import games.strategy.engine.message.RemoteMessenger;
import games.strategy.engine.message.RemoteName;
import games.strategy.engine.message.UnifiedMessenger;
import games.strategy.net.IConnectionChangeListener;
import games.strategy.net.IMessenger;
import games.strategy.net.IMessengerErrorListener;
import games.strategy.net.INode;
import games.strategy.net.IServerMessenger;
import games.strategy.net.ServerMessenger;
import games.strategy.util.Version;

public class ServerModel extends Observable implements IMessengerErrorListener, IConnectionChangeListener {
  public static final String CHAT_NAME = "games.strategy.engine.framework.ui.ServerStartup.CHAT_NAME";
  public static final RemoteName SERVER_REMOTE_NAME =
      new RemoteName("games.strategy.engine.framework.ui.ServerStartup.SERVER_REMOTE", IServerStartupRemote.class);

  public static RemoteName getObserverWaitingToStartName(final INode node) {
    return new RemoteName("games.strategy.engine.framework.startup.mc.ServerModel.OBSERVER" + node.getName(),
        IObserverWaitingToJoin.class);
  }

  final static String PLAYERNAME = "PlayerName";
  private static Logger s_logger = Logger.getLogger(ServerModel.class.getName());
  private final GameObjectStreamFactory m_objectStreamFactory = new GameObjectStreamFactory(null);
  private final SetupPanelModel m_typePanelModel;
  private final boolean m_headless;
  private IServerMessenger m_serverMessenger;
  private IRemoteMessenger m_remoteMessenger;
  private IChannelMessenger m_channelMessenger;
  private GameData m_data;
  private Map<String, String> m_playersToNodeListing = new HashMap<>();
  private Map<String, Boolean> m_playersEnabledListing = new HashMap<>();
  private Collection<String> m_playersAllowedToBeDisabled = new HashSet<>();
  private Map<String, Collection<String>> m_playerNamesAndAlliancesInTurnOrder =
      new LinkedHashMap<>();
  private IRemoteModelListener m_listener = IRemoteModelListener.NULL_LISTENER;
  private final GameSelectorModel m_gameSelectorModel;
  private Component m_ui;
  private IChatPanel m_chatPanel;
  private ChatController m_chatController;
  private final Map<String, String> m_localPlayerTypes = new HashMap<>();
  // while our server launcher is not null, delegate new/lost connections to it
  private volatile ServerLauncher m_serverLauncher;
  private CountDownLatch m_removeConnectionsLatch = null;
  private final Observer m_gameSelectorObserver = new Observer() {
    @Override
    public void update(final Observable o, final Object arg) {
      gameDataChanged();
    }
  };

  public ServerModel(final GameSelectorModel gameSelectorModel, final SetupPanelModel typePanelModel) {
    this(gameSelectorModel, typePanelModel, false);
  }

  public ServerModel(final GameSelectorModel gameSelectorModel, final SetupPanelModel typePanelModel,
      final boolean headless) {
    m_gameSelectorModel = gameSelectorModel;
    m_typePanelModel = typePanelModel;
    m_gameSelectorModel.addObserver(m_gameSelectorObserver);
    m_headless = headless;
  }

  public void shutDown() {
    m_gameSelectorModel.deleteObserver(m_gameSelectorObserver);
    if (m_serverMessenger != null) {
      m_chatController.deactivate();
      m_serverMessenger.shutDown();
      m_serverMessenger.removeErrorListener(this);
      m_chatPanel.shutDown();
    }
  }

  public void cancel() {
    m_gameSelectorModel.deleteObserver(m_gameSelectorObserver);
    if (m_serverMessenger != null) {
      m_chatController.deactivate();
      m_serverMessenger.shutDown();
      m_serverMessenger.removeErrorListener(this);
      m_chatPanel.setChat(null);
    }
  }

  public void setRemoteModelListener(IRemoteModelListener listener) {
    if (listener == null) {
      listener = IRemoteModelListener.NULL_LISTENER;
    }
    m_listener = listener;
  }

  public void setLocalPlayerType(final String player, final String type) {
    synchronized (this) {
      m_localPlayerTypes.put(player, type);
    }
  }

  private void gameDataChanged() {
    synchronized (this) {
      m_data = m_gameSelectorModel.getGameData();
      if (m_data != null) {
        m_playersToNodeListing = new HashMap<>();
        m_playersEnabledListing = new HashMap<>();
        m_playersAllowedToBeDisabled = new HashSet<>(m_data.getPlayerList().getPlayersThatMayBeDisabled());
        m_playerNamesAndAlliancesInTurnOrder = new LinkedHashMap<>();
        for (final PlayerID player : m_data.getPlayerList().getPlayers()) {
          final String name = player.getName();
          if (m_headless) {
            if (player.getIsDisabled()) {
              m_playersToNodeListing.put(name, m_serverMessenger.getLocalNode().getName());
              // the 2nd in the list should be Weak AI
              int indexPosition = Math.max(0, Math.min(m_data.getGameLoader().getServerPlayerTypes().length - 1, 1));
              m_localPlayerTypes.put(name, m_data.getGameLoader().getServerPlayerTypes()[indexPosition]);
            } else {
              // we generally do not want a headless host bot to be doing any AI turns, since that
              // is taxing on the system
              m_playersToNodeListing.put(name, null);
            }
          } else {
            m_playersToNodeListing.put(name, m_serverMessenger.getLocalNode().getName());
          }
          m_playerNamesAndAlliancesInTurnOrder.put(name, m_data.getAllianceTracker().getAlliancesPlayerIsIn(player));
          m_playersEnabledListing.put(name, !player.getIsDisabled());
        }
      }
      m_objectStreamFactory.setData(m_data);
      m_localPlayerTypes.clear();
    }
    notifyChanellPlayersChanged();
    m_listener.playerListChanged();
  }

  private ServerProps getServerProps(final Component ui) {
    if (System.getProperties().getProperty(GameRunner2.TRIPLEA_SERVER_PROPERTY, "false").equals("true")
        && System.getProperties().getProperty(GameRunner2.TRIPLEA_STARTED, "").equals("")) {
      final ServerProps props = new ServerProps();
      props.setName(System.getProperty(GameRunner2.TRIPLEA_NAME_PROPERTY));
      props.setPort(Integer.parseInt(System.getProperty(GameRunner2.TRIPLEA_PORT_PROPERTY)));
      if (System.getProperty(GameRunner2.TRIPLEA_SERVER_PASSWORD_PROPERTY) != null) {
        props.setPassword(System.getProperty(GameRunner2.TRIPLEA_SERVER_PASSWORD_PROPERTY));
      }
      System.setProperty(GameRunner2.TRIPLEA_STARTED, "true");
      return props;
    }
    final Preferences prefs = Preferences.userNodeForPackage(this.getClass());
    final String playername = prefs.get(PLAYERNAME, System.getProperty("user.name"));
    final ServerOptions options = new ServerOptions(ui, playername, GameRunner2.PORT, false);
    options.setLocationRelativeTo(ui);
    options.setVisible(true);
    options.dispose();
    if (!options.getOKPressed()) {
      return null;
    }
    final String name = options.getName();
    s_logger.log(Level.FINE, "Server playing as:" + name);
    // save the name! -- lnxduk
    prefs.put(PLAYERNAME, name);
    final int port = options.getPort();
    if (port >= 65536 || port == 0) {
      if (m_headless) {
        System.out.println("Invalid Port: " + port);
      } else {
        JOptionPane.showMessageDialog(ui, "Invalid Port: " + port, "Error", JOptionPane.ERROR_MESSAGE);
      }
      return null;
    }
    final ServerProps props = new ServerProps();
    props.setName(options.getName());
    props.setPort(options.getPort());
    props.setPassword(options.getPassword());
    return props;
  }

  /**
   * UI can be null. We use it as the parent for message dialogs we show.
   * If you have a component displayed, use it.
   */
  public boolean createServerMessenger(Component ui) {
    ui = ui == null ? null : JOptionPane.getFrameForComponent(ui);
    m_ui = ui;
    final ServerProps props = getServerProps(ui);
    if (props == null) {
      return false;
    }
    try {
      m_serverMessenger = new ServerMessenger(props.getName(), props.getPort(), m_objectStreamFactory);
      final ClientLoginValidator clientLoginValidator = new ClientLoginValidator(m_serverMessenger);
      clientLoginValidator.setGamePassword(props.getPassword());
      m_serverMessenger.setLoginValidator(clientLoginValidator);
      m_serverMessenger.addErrorListener(this);
      m_serverMessenger.addConnectionChangeListener(this);
      final UnifiedMessenger unifiedMessenger = new UnifiedMessenger(m_serverMessenger);
      m_remoteMessenger = new RemoteMessenger(unifiedMessenger);
      m_remoteMessenger.registerRemote(m_serverStartupRemote, SERVER_REMOTE_NAME);
      m_channelMessenger = new ChannelMessenger(unifiedMessenger);
      final NullModeratorController moderatorController = new NullModeratorController(m_serverMessenger, null);
      moderatorController.register(m_remoteMessenger);
      m_chatController =
          new ChatController(CHAT_NAME, m_serverMessenger, m_remoteMessenger, m_channelMessenger, moderatorController);

      if (ui == null && m_headless) {
        m_chatPanel = new HeadlessChat(m_serverMessenger, m_channelMessenger, m_remoteMessenger, CHAT_NAME,
            Chat.CHAT_SOUND_PROFILE.GAME_CHATROOM);
      } else {
        m_chatPanel = new ChatPanel(m_serverMessenger, m_channelMessenger, m_remoteMessenger, CHAT_NAME,
            Chat.CHAT_SOUND_PROFILE.GAME_CHATROOM);
      }

      m_serverMessenger.setAcceptNewConnections(true);
      gameDataChanged();
      return true;
    } catch (final IOException ioe) {
      ioe.printStackTrace(System.out);
      if (m_headless) {
        System.out.println("Unable to create server socket:" + ioe.getMessage());
      } else {
        JOptionPane.showMessageDialog(ui, "Unable to create server socket:" + ioe.getMessage(), "Error",
            JOptionPane.ERROR_MESSAGE);
      }
      return false;
    }
  }

  private final IServerStartupRemote m_serverStartupRemote = new IServerStartupRemote() {
    @Override
    public PlayerListing getPlayerListing() {
      return getPlayerListingInternal();
    }

    @Override
    public void takePlayer(final INode who, final String playerName) {
      takePlayerInternal(who, true, playerName);
    }

    @Override
    public void releasePlayer(final INode who, final String playerName) {
      takePlayerInternal(who, false, playerName);
    }

    @Override
    public void disablePlayer(final String playerName) {
      if (!m_headless) {
        return;
      }
      // we don't want the client's changing stuff for anyone but a bot
      setPlayerEnabled(playerName, false);
    }

    @Override
    public void enablePlayer(final String playerName) {
      if (!m_headless) {
        return;
      }
      // we don't want the client's changing stuff for anyone but a bot
      setPlayerEnabled(playerName, true);
    }

    @Override
    public boolean isGameStarted(final INode newNode) {
      if (m_serverLauncher != null) {
        final RemoteName remoteName = getObserverWaitingToStartName(newNode);
        final IObserverWaitingToJoin observerWaitingToJoinBlocking =
            (IObserverWaitingToJoin) m_remoteMessenger.getRemote(remoteName);
        final IObserverWaitingToJoin observerWaitingToJoinNonBlocking =
            (IObserverWaitingToJoin) m_remoteMessenger.getRemote(remoteName, true);
        m_serverLauncher.addObserver(observerWaitingToJoinBlocking, observerWaitingToJoinNonBlocking, newNode);
        return true;
      } else {
        return false;
      }
    }

    @Override
    public boolean getIsServerHeadless() {
      return HeadlessGameServer.headless();
    }

    /**
     * This should not be called from within game, only from the game setup screen, while everyone is waiting for game
     * to start
     */
    @Override
    public byte[] getSaveGame() {
      System.out.println("Sending save game");

      byte[] bytes = null;
      try (final ByteArrayOutputStream sink = new ByteArrayOutputStream(5000)) {
        new GameDataManager().saveGame(sink, m_data);
        bytes = sink.toByteArray();
      } catch (final IOException e) {
        e.printStackTrace();
        throw new IllegalStateException(e);
      }
      return bytes;
    }

    @Override
    public byte[] getGameOptions() {
      byte[] bytes = null;
      if (m_data == null || m_data.getProperties() == null || m_data.getProperties().getEditableProperties() == null
          || m_data.getProperties().getEditableProperties().isEmpty()) {
        return bytes;
      }
      final List<IEditableProperty> currentEditableProperties = m_data.getProperties().getEditableProperties();

      try (final ByteArrayOutputStream sink = new ByteArrayOutputStream(1000)) {
        GameProperties.toOutputStream(sink, currentEditableProperties);
        bytes = sink.toByteArray();
      } catch (final IOException e) {
        e.printStackTrace();
      }
      return bytes;
    }

    @Override
    public Set<String> getAvailableGames() {
      final HeadlessGameServer headless = HeadlessGameServer.getInstance();
      if (headless == null) {
        return null;
      }
      return headless.getAvailableGames();
    }

    @Override
    public void changeServerGameTo(final String gameName) {
      final HeadlessGameServer headless = HeadlessGameServer.getInstance();
      if (headless == null) {
        return;
      }
      System.out.println("Changing to game map: " + gameName);
      headless.setGameMapTo(gameName);
    }

    @Override
    public void changeToLatestAutosave(final SaveGameFileChooser.AUTOSAVE_TYPE typeOfAutosave) {
      final HeadlessGameServer headless = HeadlessGameServer.getInstance();
      if (headless == null) {
        return;
      }
      final File save;
      if (SaveGameFileChooser.AUTOSAVE_TYPE.AUTOSAVE.equals(typeOfAutosave)) {
        save = new File(SaveGameFileChooser.DEFAULT_DIRECTORY, SaveGameFileChooser.getAutoSaveFileName());
      } else if (SaveGameFileChooser.AUTOSAVE_TYPE.AUTOSAVE2.equals(typeOfAutosave)) {
        save = new File(SaveGameFileChooser.DEFAULT_DIRECTORY, SaveGameFileChooser.getAutoSave2FileName());
      } else if (SaveGameFileChooser.AUTOSAVE_TYPE.AUTOSAVE_ODD.equals(typeOfAutosave)) {
        save = new File(SaveGameFileChooser.DEFAULT_DIRECTORY, SaveGameFileChooser.getAutoSaveOddFileName());
      } else if (SaveGameFileChooser.AUTOSAVE_TYPE.AUTOSAVE_EVEN.equals(typeOfAutosave)) {
        save = new File(SaveGameFileChooser.DEFAULT_DIRECTORY, SaveGameFileChooser.getAutoSaveEvenFileName());
      } else {
        return;
      }
      if (save == null || !save.exists()) {
        return;
      }
      System.out.println("Changing to autosave of type: " + typeOfAutosave.toString());
      headless.loadGameSave(save);
    }

    @Override
    public void changeToGameSave(final byte[] bytes, final String fileName) {
      // TODO: change to a string message return, so we can tell the user/requestor if it was successful or not, and why
      // if not.
      final HeadlessGameServer headless = HeadlessGameServer.getInstance();
      if (headless == null || bytes == null) {
        return;
      }
      System.out.println("Changing to user savegame: " + fileName);
      try (ByteArrayInputStream input = new ByteArrayInputStream(bytes);
          InputStream oinput = new BufferedInputStream(input);) {
        headless.loadGameSave(oinput, fileName);
      } catch (final Exception e) {
        e.printStackTrace();
      }
    }

    @Override
    public void changeToGameOptions(final byte[] bytes) {
      // TODO: change to a string message return, so we can tell the user/requestor if it was successful or not, and why
      // if not.
      final HeadlessGameServer headless = HeadlessGameServer.getInstance();
      if (headless == null || bytes == null) {
        return;
      }
      System.out.println("Changing to user game options.");
      try {
        headless.loadGameOptions(bytes);
      } catch (final Exception e) {
        e.printStackTrace();
      }
    }
  };

  private PlayerListing getPlayerListingInternal() {
    synchronized (this) {
      if (m_data == null) {
        return new PlayerListing(new HashMap<>(), new HashMap<>(m_playersEnabledListing),
            getLocalPlayerTypes(), new Version(0, 0), m_gameSelectorModel.getGameName(),
            m_gameSelectorModel.getGameRound(), new HashSet<>(m_playersAllowedToBeDisabled),
            new LinkedHashMap<>());
      } else {
        return new PlayerListing(new HashMap<>(m_playersToNodeListing),
            new HashMap<>(m_playersEnabledListing), getLocalPlayerTypes(), m_data.getGameVersion(),
            m_data.getGameName(), m_data.getSequence().getRound() + "",
            new HashSet<>(m_playersAllowedToBeDisabled), m_playerNamesAndAlliancesInTurnOrder);
      }
    }
  }

  private void takePlayerInternal(final INode from, final boolean take, final String playerName) {
    // synchronize to make sure two adds arent executed at once
    synchronized (this) {
      if (!m_playersToNodeListing.containsKey(playerName)) {
        return;
      }
      if (take) {
        m_playersToNodeListing.put(playerName, from.getName());
      } else {
        m_playersToNodeListing.put(playerName, null);
      }
    }
    notifyChanellPlayersChanged();
    m_listener.playersTakenChanged();
  }

  private void setPlayerEnabled(final String playerName, final boolean enabled) {
    takePlayerInternal(m_serverMessenger.getLocalNode(), true, playerName);
    // synchronize
    synchronized (this) {
      if (!m_playersEnabledListing.containsKey(playerName)) {
        return;
      }
      m_playersEnabledListing.put(playerName, enabled);
      if (m_headless) {
        // we do not want the host bot to actually play, so set to null if enabled, and set to weak ai if disabled
        if (enabled) {
          m_playersToNodeListing.put(playerName, null);
        } else {
          m_localPlayerTypes.put(playerName,
              m_data.getGameLoader().getServerPlayerTypes()[Math.max(0,
                  // the 2nd in the list should be Weak AI
                  Math.min(m_data.getGameLoader().getServerPlayerTypes().length - 1, 1))]);
        }
      }
    }
    notifyChanellPlayersChanged();
    m_listener.playersTakenChanged();
  }

  public void setAllPlayersToNullNodes() {
    if (m_playersToNodeListing != null) {
      for (final String p : m_playersToNodeListing.keySet()) {
        m_playersToNodeListing.put(p, null);
      }
    }
  }

  private void notifyChanellPlayersChanged() {
    final IClientChannel channel =
        (IClientChannel) m_channelMessenger.getChannelBroadcastor(IClientChannel.CHANNEL_NAME);
    channel.playerListingChanged(getPlayerListingInternal());
  }

  public void takePlayer(final String playerName) {
    takePlayerInternal(m_serverMessenger.getLocalNode(), true, playerName);
  }

  public void releasePlayer(final String playerName) {
    takePlayerInternal(m_serverMessenger.getLocalNode(), false, playerName);
  }

  public void disablePlayer(final String playerName) {
    setPlayerEnabled(playerName, false);
  }

  public void enablePlayer(final String playerName) {
    setPlayerEnabled(playerName, true);
  }

  public IServerMessenger getMessenger() {
    return m_serverMessenger;
  }

  public Map<String, String> getPlayersToNodeListing() {
    synchronized (this) {
      return new HashMap<>(m_playersToNodeListing);
    }
  }

  public Map<String, Boolean> getPlayersEnabledListing() {
    synchronized (this) {
      return new HashMap<>(m_playersEnabledListing);
    }
  }

  public Collection<String> getPlayersAllowedToBeDisabled() {
    synchronized (this) {
      return new HashSet<>(m_playersAllowedToBeDisabled);
    }
  }

  public Map<String, Collection<String>> getPlayerNamesAndAlliancesInTurnOrderLinkedHashMap() {
    synchronized (this) {
      return new LinkedHashMap<>(m_playerNamesAndAlliancesInTurnOrder);
    }
  }

  @Override
  public void messengerInvalid(final IMessenger messenger, final Exception reason) {
    if (m_headless) {
      System.out.println("Connection Lost");
      if (m_typePanelModel != null) {
        m_typePanelModel.showSelectType();
      }
    } else {
      JOptionPane.showMessageDialog(m_ui, "Connection lost", "Error", JOptionPane.ERROR_MESSAGE);
      m_typePanelModel.showSelectType();
    }
  }

  @Override
  public void connectionAdded(final INode to) {}

  @Override
  public void connectionRemoved(final INode node) {
    if (m_removeConnectionsLatch != null) {
      try {
        m_removeConnectionsLatch.await(6, TimeUnit.SECONDS);
      } catch (final InterruptedException e) {// no worries
      }
    }
    // will be handled elsewhere
    if (m_serverLauncher != null) {
      m_serverLauncher.connectionLost(node);
      return;
    }
    // we lost a node. Remove the players he plays.
    final List<String> free = new ArrayList<>();
    synchronized (this) {
      for (final String player : m_playersToNodeListing.keySet()) {
        final String playedBy = m_playersToNodeListing.get(player);
        if (playedBy != null && playedBy.equals(node.getName())) {
          free.add(player);
        }
      }
    }
    for (final String player : free) {
      takePlayerInternal(node, false, player);
    }
  }

  public IChatPanel getChatPanel() {
    return m_chatPanel;
  }

  public void disallowRemoveConnections() {
    while (m_removeConnectionsLatch != null && m_removeConnectionsLatch.getCount() > 0) {
      m_removeConnectionsLatch.countDown();
    }
    m_removeConnectionsLatch = new CountDownLatch(1);
  }

  public void allowRemoveConnections() {
    while (m_removeConnectionsLatch != null && m_removeConnectionsLatch.getCount() > 0) {
      m_removeConnectionsLatch.countDown();
    }
    m_removeConnectionsLatch = null;
  }

  public Map<String, String> getLocalPlayerTypes() {
    final Map<String, String> localPlayerMappings = new HashMap<>();
    if (m_data == null) {
      return localPlayerMappings;
    }
    // local player default = humans (for bots = weak ai)
    final String defaultLocalType = m_headless
        ? m_data.getGameLoader().getServerPlayerTypes()[Math.max(0,
            Math.min(m_data.getGameLoader().getServerPlayerTypes().length - 1, 1))]
        : m_data.getGameLoader().getServerPlayerTypes()[0];
    for (final String player : m_playersToNodeListing.keySet()) {
      final String playedBy = m_playersToNodeListing.get(player);
      if (playedBy == null) {
        continue;
      }
      if (playedBy.equals(m_serverMessenger.getLocalNode().getName())) {
        String type = defaultLocalType;
        if (m_localPlayerTypes.containsKey(player)) {
          type = m_localPlayerTypes.get(player);
        }
        localPlayerMappings.put(player, type);
      }
    }
    return localPlayerMappings;
  }

  public ILauncher getLauncher() {
    synchronized (this) {
      disallowRemoveConnections();
      // -1 since we dont count outselves
      final int clientCount = m_serverMessenger.getNodes().size() - 1;
      final Map<String, INode> remotePlayers = new HashMap<>();
      for (final String player : m_playersToNodeListing.keySet()) {
        final String playedBy = m_playersToNodeListing.get(player);
        if (playedBy == null) {
          return null;
        }
        if (!playedBy.equals(m_serverMessenger.getLocalNode().getName())) {
          final Set<INode> nodes = m_serverMessenger.getNodes();
          for (final INode node : nodes) {
            if (node.getName().equals(playedBy)) {
              remotePlayers.put(player, node);
              break;
            }
          }
        }
      }
      final ServerLauncher launcher = new ServerLauncher(clientCount, m_remoteMessenger, m_channelMessenger,
          m_serverMessenger, m_gameSelectorModel, getPlayerListingInternal(), remotePlayers, this, m_headless);
      return launcher;
    }
  }

  public void newGame() {
    m_serverMessenger.setAcceptNewConnections(true);
    final IClientChannel channel =
        (IClientChannel) m_channelMessenger.getChannelBroadcastor(IClientChannel.CHANNEL_NAME);
    notifyChanellPlayersChanged();
    channel.gameReset();
  }

  public void setServerLauncher(final ServerLauncher launcher) {
    m_serverLauncher = launcher;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    sb.append("ServerModel GameData:").append(m_data == null ? "null" : m_data.getGameName()).append("\n");
    sb.append("Connected:").append(m_serverMessenger == null ? "null" : m_serverMessenger.isConnected()).append("\n");
    sb.append(m_serverMessenger);
    sb.append("\n");
    sb.append(m_remoteMessenger);
    sb.append("\n");
    sb.append(m_channelMessenger);
    return sb.toString();
  }
}


class ServerProps {
  private String name;
  private int port;
  private String password;

  public String getPassword() {
    return password;
  }

  public void setPassword(final String password) {
    this.password = password;
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public int getPort() {
    return port;
  }

  public void setPort(final int port) {
    this.port = port;
  }
}
