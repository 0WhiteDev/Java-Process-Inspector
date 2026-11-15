package dev.whitedev.jpi.attach;

import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.protocol.WireProtocol;
import dev.whitedev.jpi.ui.search.SearchEverythingPanel;
import dev.whitedev.jpi.ui.timeline.RuntimeTimelineStore;
import org.junit.jupiter.api.Test;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class SearchEverythingSessionTest {
    @Test void coalescesQueriesAndDiscardsThePreviousResponse() throws Exception {
        CountDownLatch first = new CountDownLatch(1), release = new CountDownLatch(1), displayed = new CountDownLatch(1);
        List<String> queries = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<Void> finished = new CompletableFuture<>();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
             Socket peer = listener.accept();
             InspectorSession session = new InspectorSession(new JvmDescriptor("123", "search fixture"), socket)) {
            Thread.ofVirtual().start(() -> {
                try {
                    var input = new DataInputStream(peer.getInputStream());
                    var output = new DataOutputStream(peer.getOutputStream());
                    while (true) {
                        var request = WireProtocol.readRequest(input);
                        if (request.operation() == Operation.DISCONNECT) break;
                        String response;
                        if (request.operation() == Operation.SEARCH_EVERYTHING) {
                            String query = WireProtocol.text(request.payload());
                            queries.add(query);
                            if (query.equals("old")) { first.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); }
                            response = "S\t1\t1\t1\t0\t64\tfalse\tfalse\nC\tCLASSES\t1\tfalse\nR\tCLASSES\t"
                                    + encoded("c:" + query) + '\t' + encoded(query) + "\t\t\t" + encoded("loader") + '\t' + encoded(query) + '\n';
                        } else {
                            assertEquals(Operation.EXCEPTION_SNAPSHOT, request.operation());
                            response = "S\t1\tidle\t30\t0\t\n";
                        }
                        WireProtocol.writeResponse(output, true, WireProtocol.utf8(response));
                    }
                    finished.complete(null);
                } catch (Throwable error) { finished.completeExceptionally(error); }
            });
            AtomicReference<SearchEverythingPanel> reference = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                var panel = new SearchEverythingPanel(new DeobfuscationWorkspace(), new RuntimeTimelineStore(), () -> null, ignored -> {});
                reference.set(panel);
                panel.setSession(session);
                JTable table = find(panel, JTable.class);
                table.getModel().addTableModelListener(event -> {
                    if (table.getRowCount() > 0 && "new".equals(table.getValueAt(0, 1))) displayed.countDown();
                });
                submit(panel, "old");
            });
            try {
                assertTrue(first.await(3, TimeUnit.SECONDS));
                SwingUtilities.invokeAndWait(() -> { submit(reference.get(), "middle"); submit(reference.get(), "new"); });
                release.countDown();
                assertTrue(displayed.await(5, TimeUnit.SECONDS));
                SwingUtilities.invokeAndWait(() -> assertEquals("new", find(reference.get(), JTable.class).getValueAt(0, 1)));
                assertEquals(List.of("old", "new"), queries);
            } finally {
                release.countDown();
                SwingUtilities.invokeAndWait(() -> reference.get().close());
                session.close();
                finished.get(3, TimeUnit.SECONDS);
            }
        }
    }

    private static void submit(SearchEverythingPanel panel, String text) {
        JTextField query = find(panel, JTextField.class);
        query.setText(text);
        query.postActionEvent();
    }
    private static String encoded(String value) { return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static <T> T find(Container root, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) return type.cast(component);
            if (component instanceof Container container) {
                T match = find(container, type);
                if (match != null) return match;
            }
        }
        return null;
    }
}
