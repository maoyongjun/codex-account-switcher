package com.juchat.codexswitcher;

import com.juchat.codexswitcher.model.AccountSummary;
import com.juchat.codexswitcher.model.LaunchResult;
import com.juchat.codexswitcher.model.RestoreMode;
import com.juchat.codexswitcher.model.RestoreResult;
import com.juchat.codexswitcher.service.AccountRepository;
import com.juchat.codexswitcher.service.AppPaths;
import com.juchat.codexswitcher.service.AuthTokenParser;
import com.juchat.codexswitcher.service.CursorLauncher;
import com.juchat.codexswitcher.service.LinkService;
import com.juchat.codexswitcher.service.MigrationService;
import com.juchat.codexswitcher.service.ProcessManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

public final class CodexAccountSwitcherApp extends Application {
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final ObservableList<AccountSummary> accounts = FXCollections.observableArrayList();

    private AppPaths paths;
    private AccountRepository accountRepository;
    private CursorLauncher cursorLauncher;
    private MigrationService migrationService;

    private ListView<AccountSummary> accountList;
    private Label slotValue;
    private Label emailValue;
    private Label expiresValue;
    private Label accountHomeValue;
    private Label sharedHomeValue;
    private Label statusValue;
    private TextArea logArea;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        initializeServices();

        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setLeft(buildAccountList());
        root.setCenter(buildDetailPane(stage));

        Scene scene = new Scene(root, 980, 640);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

        stage.setTitle("Codex Account Switcher");
        stage.setMinWidth(900);
        stage.setMinHeight(560);
        stage.setScene(scene);
        stage.show();

        refreshAccounts();
        accountList.getSelectionModel().selectFirst();
        appendLog("应用已启动，用户目录：" + paths.userHome());
    }

    private void initializeServices() {
        paths = AppPaths.fromSystem();
        AuthTokenParser parser = new AuthTokenParser();
        LinkService linkService = new LinkService(paths);
        accountRepository = new AccountRepository(paths, parser, linkService);
        ProcessManager processManager = new ProcessManager();
        cursorLauncher = new CursorLauncher(paths, processManager);
        migrationService = new MigrationService(paths, accountRepository, processManager);
    }

    private ListView<AccountSummary> buildAccountList() {
        accountList = new ListView<>(accounts);
        accountList.getStyleClass().add("account-list");
        accountList.setPrefWidth(285);
        accountList.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        accountList.setCellFactory(list -> new AccountCell());
        accountList.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> updateDetails(newValue));
        return accountList;
    }

    private VBox buildDetailPane(Stage stage) {
        Label title = new Label("Codex 账号切换器");
        title.getStyleClass().add("title");

        GridPane grid = new GridPane();
        grid.getStyleClass().add("detail-grid");
        grid.setHgap(12);
        grid.setVgap(10);
        slotValue = valueLabel();
        emailValue = valueLabel();
        expiresValue = valueLabel();
        accountHomeValue = valueLabel();
        sharedHomeValue = valueLabel();
        statusValue = valueLabel();
        addRow(grid, 0, "账号槽位", slotValue);
        addRow(grid, 1, "登录邮箱", emailValue);
        addRow(grid, 2, "令牌过期", expiresValue);
        addRow(grid, 3, "账号目录", accountHomeValue);
        addRow(grid, 4, "共享目录", sharedHomeValue);
        addRow(grid, 5, "目录状态", statusValue);

        FlowPane actions = new FlowPane(10, 10);
        actions.setAlignment(Pos.CENTER_LEFT);
        Button launchCursorButton = actionButton("启动 Cursor");
        Button launchCodexButton = actionButton("启动 Codex");
        Button prepareButton = actionButton("准备全部账号");
        Button clearLoginButton = actionButton("清空登录");
        Button exportButton = actionButton("导出全部账号");
        Button restoreButton = actionButton("恢复账号包");
        Button refreshButton = actionButton("刷新状态");
        launchCursorButton.setOnAction(event -> launchSelectedAccount(false));
        launchCodexButton.setOnAction(event -> launchSelectedAccount(true));
        prepareButton.setOnAction(event -> prepareAllAccounts());
        clearLoginButton.setOnAction(event -> clearSelectedAccountAuthentication());
        exportButton.setOnAction(event -> exportAccounts(stage));
        restoreButton.setOnAction(event -> restoreAccounts(stage));
        refreshButton.setOnAction(event -> refreshAccounts());
        actions.getChildren().addAll(launchCursorButton, launchCodexButton, prepareButton,
                clearLoginButton, exportButton, restoreButton, refreshButton);

        Label logTitle = new Label("最近操作日志");
        logTitle.getStyleClass().add("section-title");
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.getStyleClass().add("log-area");
        VBox.setVgrow(logArea, Priority.ALWAYS);

        VBox content = new VBox(18, title, grid, actions, logTitle, logArea);
        content.getStyleClass().add("content");
        content.setPadding(new Insets(24));
        return content;
    }

    private Button actionButton(String text) {
        Button button = new Button(text);
        button.disableProperty().bind(busy);
        button.getStyleClass().add("action-button");
        return button;
    }

    private Label valueLabel() {
        Label label = new Label("-");
        label.getStyleClass().add("value-label");
        label.setWrapText(true);
        return label;
    }

    private void addRow(GridPane grid, int row, String name, Label value) {
        Label label = new Label(name);
        label.getStyleClass().add("field-label");
        grid.add(label, 0, row);
        grid.add(value, 1, row);
        GridPane.setHgrow(value, Priority.ALWAYS);
    }

    private void refreshAccounts() {
        List<AccountSummary> refreshed = accountRepository.listAccounts();
        int selectedSlot = selectedAccount() == null ? 1 : selectedAccount().getSlot();
        accounts.setAll(refreshed);
        accounts.stream()
                .filter(account -> account.getSlot() == selectedSlot)
                .findFirst()
                .ifPresent(account -> accountList.getSelectionModel().select(account));
        updateDetails(selectedAccount());
    }

    private void updateDetails(AccountSummary account) {
        if (account == null) {
            slotValue.setText("-");
            emailValue.setText("-");
            expiresValue.setText("-");
            accountHomeValue.setText("-");
            sharedHomeValue.setText(paths.sharedHome().toString());
            statusValue.setText("-");
            return;
        }
        slotValue.setText("Account " + account.getSlot());
        emailValue.setText(account.displayEmail());
        expiresValue.setText(account.getExpires().isBlank() ? "-" : account.getExpires());
        accountHomeValue.setText(account.getHome().toString());
        sharedHomeValue.setText(paths.sharedHome().toString());
        statusValue.setText(account.isPrepared() ? "已准备" : "未准备");
    }

    private AccountSummary selectedAccount() {
        return accountList == null ? null : accountList.getSelectionModel().getSelectedItem();
    }

    private void launchSelectedAccount(boolean codexApp) {
        AccountSummary selected = selectedAccount();
        if (selected == null) {
            showWarning("请先选择一个账号。");
            return;
        }
        String target = codexApp ? "Codex" : "Cursor";
        runBackground("启动 " + target + " 账号 " + selected.getSlot(), () -> {
            Path accountHome = accountRepository.prepareSlot(selected.getSlot());
            if (codexApp) {
                cursorLauncher.stopRunningApps();
                accountRepository.activateSlotForDefaultCodexHome(selected.getSlot());
            }
            LaunchResult result = codexApp
                    ? cursorLauncher.launchCodexApp(selected.getSlot(), accountHome, false)
                    : cursorLauncher.launchCursor(selected.getSlot(), accountHome);
            return "已启动 Account " + result.getSlot() + "，" + result.getTargetName() + "：" + result.getExecutablePath();
        }, true);
    }

    private void prepareAllAccounts() {
        runBackground("准备全部账号", () -> {
            accountRepository.prepareAll();
            return "已准备 1-" + AccountRepository.MAX_ACCOUNTS + " 个账号目录。";
        }, true);
    }

    private void clearSelectedAccountAuthentication() {
        AccountSummary selected = selectedAccount();
        if (selected == null) {
            showWarning("请先选择一个账号。");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认清空登录");
        confirm.setHeaderText("清空 Account " + selected.getSlot() + " 的登录凭证？");
        confirm.setContentText("这只会清空登录信息，不会删除配置、会话或共享数据。");
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }

        runBackground("清空 Account " + selected.getSlot() + " 登录凭证", () -> {
            accountRepository.clearSlotAuthentication(selected.getSlot());
            return "已清空 Account " + selected.getSlot() + " 的登录凭证。";
        }, true);
    }

    private void exportAccounts(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出 Codex 账号");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Zip package", "*.zip"));
        chooser.setInitialFileName("codex-accounts-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) + ".zip");
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        Path zip = file.toPath();
        runBackground("导出全部账号", () -> {
            Path exported = migrationService.exportAll(zip);
            return "导出完成：" + exported;
        }, true);
    }

    private void restoreAccounts(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("恢复 Codex 账号包");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Zip package", "*.zip"));
        File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认恢复");
        confirm.setHeaderText("恢复会先备份当前账号数据，然后用导出包替换。");
        confirm.setContentText("导出包：" + file.toPath());
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }

        runBackground("恢复账号包", () -> {
            RestoreResult result = migrationService.restore(file.toPath(), RestoreMode.BACKUP_THEN_REPLACE);
            return "恢复完成，原数据备份在：" + result.getBackupRoot();
        }, true);
    }

    private void runBackground(String title, Callable<String> action, boolean refreshAfter) {
        appendLog(title + "...");
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return action.call();
            }
        };
        task.setOnRunning(event -> busy.set(true));
        task.setOnSucceeded(event -> {
            busy.set(false);
            appendLog(task.getValue());
            if (refreshAfter) {
                refreshAccounts();
            }
        });
        task.setOnFailed(event -> {
            busy.set(false);
            Throwable error = task.getException();
            String message = error == null ? "未知错误" : error.getMessage();
            appendLog(title + "失败：" + message);
            showError(title + "失败", message, error);
            if (refreshAfter) {
                refreshAccounts();
            }
        });
        Thread thread = new Thread(task, "codex-switcher-task");
        thread.setDaemon(true);
        thread.start();
    }

    private void appendLog(String message) {
        String line = "[" + LOG_TIME.format(LocalDateTime.now()) + "] " + message + System.lineSeparator();
        if (Platform.isFxApplicationThread()) {
            logArea.appendText(line);
        } else {
            Platform.runLater(() -> logArea.appendText(line));
        }
    }

    private void showWarning(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private void showError(String title, String message, Throwable error) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message == null ? "" : message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.showAndWait();
    }

    private static final class AccountCell extends ListCell<AccountSummary> {
        @Override
        protected void updateItem(AccountSummary account, boolean empty) {
            super.updateItem(account, empty);
            if (empty || account == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label title = new Label("Account " + account.getSlot());
            title.getStyleClass().add("account-title");
            Label detail = new Label(account.displayEmail());
            detail.getStyleClass().add("account-subtitle");
            VBox box = new VBox(4, title, detail);
            box.setPadding(new Insets(8, 6, 8, 6));
            setGraphic(box);
        }
    }
}
