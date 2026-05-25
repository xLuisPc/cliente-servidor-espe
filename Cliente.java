/**
 * Cliente.java
 * ─────────────────────────────────────────────────────────────────────────────
 * Interfaz gráfica del cliente de chat.
 *
 * FLUJO DE LA APLICACIÓN:
 *   1. Al iniciar: pedir nombre de usuario (JOptionPane).
 *   2. Mostrar panel de descubrimiento: busca servidores en la red local.
 *   3. Usuario selecciona servidor (o escribe IP manualmente) → conectar.
 *   4. Al conectar: el servidor envía el historial (mensajes y archivos previos).
 *   5. Chat normal. Botón "Cambiar servidor" → volver al paso 2.
 *
 * HILOS:
 *   - EDT (Event Dispatch Thread): todo lo de Swing. Nunca bloquear aquí.
 *   - Hilo descubrimiento: envía UDP broadcast y espera respuestas (2 seg).
 *   - Hilo escucha: bloquea en readUTF() esperando mensajes del servidor.
 *     Se lanza uno nuevo por cada conexión; el anterior muere al cerrar el socket.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class Cliente extends JFrame {

    // ── Estado de la conexión ────────────────────────────────────────────────
    private DataInputStream  entrada;
    private DataOutputStream salida;
    private Socket           socketActual;
    private String           usuario;

    // Flag que indica que la desconexión fue intencional (cambio de servidor).
    // El hilo de escucha lo revisa al recibir la IOException del socket cerrado:
    // si es true, no muestra "conexión perdida".
    private volatile boolean cambiandoServidor = false;

    // Archivos recibidos en memoria, pendientes de guardar.
    // ConcurrentHashMap: el hilo de escucha escribe, el EDT lee al presionar "Descargar".
    private final Map<String, byte[]> archivosEnMemoria = new ConcurrentHashMap<>();

    // ── Componentes UI ───────────────────────────────────────────────────────
    private CardLayout cardLayout;
    private JPanel     panelRaiz;

    // Panel de descubrimiento
    private DefaultListModel<ServidorInfo> modeloServidores;
    private JList<ServidorInfo>            listaServidores;
    private JButton                        btnBuscar;
    private JLabel                         lblEstado;
    private JTextField                     campoIP;
    private JTextField                     campoPuerto;

    // Panel de chat
    private JTextPane  areaChat;
    private JTextField campoMensaje;
    private JLabel     lblServidor;

    // ── Constructor ──────────────────────────────────────────────────────────

    public Cliente() {
        usuario = JOptionPane.showInputDialog(
                null, "Ingrese su nombre de usuario:", "Chat", JOptionPane.PLAIN_MESSAGE);
        if (usuario == null || usuario.trim().isEmpty()) System.exit(0);
        usuario = usuario.trim();

        setTitle("Chat — " + usuario);
        setSize(680, 520);
        setMinimumSize(new Dimension(450, 380));
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { cerrar(); }
        });

        construirInterfaz();
        setLocationRelativeTo(null);
        setVisible(true);

        // Al iniciar, buscar servidores automáticamente.
        buscarServidores();
    }

    // ─── Construcción de la interfaz ─────────────────────────────────────────

    private void construirInterfaz() {
        // CardLayout permite cambiar entre "DESCUBRIMIENTO" y "CHAT"
        // con una sola llamada, como voltear una tarjeta.
        cardLayout = new CardLayout();
        panelRaiz  = new JPanel(cardLayout);

        panelRaiz.add(construirPanelDescubrimiento(), "DESCUBRIMIENTO");
        panelRaiz.add(construirPanelChat(),           "CHAT");

        add(panelRaiz);
        cardLayout.show(panelRaiz, "DESCUBRIMIENTO");
    }

    // ── Panel de descubrimiento ──────────────────────────────────────────────

    private JPanel construirPanelDescubrimiento() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // ── Título ──
        JLabel titulo = new JLabel("Servidores disponibles en la red local");
        titulo.setFont(new Font("SansSerif", Font.BOLD, 14));
        panel.add(titulo, BorderLayout.NORTH);

        // ── Lista de servidores encontrados ──
        modeloServidores = new DefaultListModel<>();
        listaServidores  = new JList<>(modeloServidores);
        listaServidores.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        listaServidores.setFont(new Font("Monospaced", Font.PLAIN, 13));
        // Doble clic en un servidor → conectar directamente
        listaServidores.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) intentarConexion();
            }
        });
        panel.add(new JScrollPane(listaServidores), BorderLayout.CENTER);

        // ── Panel inferior: conexión manual + botones ──
        JPanel panelInferior = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill   = GridBagConstraints.HORIZONTAL;

        // Fila 1: IP manual y puerto
        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        panelInferior.add(new JLabel("IP manual:"), c);
        c.gridx = 1; c.weightx = 1;
        campoIP = new JTextField("localhost");
        panelInferior.add(campoIP, c);
        c.gridx = 2; c.weightx = 0;
        panelInferior.add(new JLabel("Puerto:"), c);
        c.gridx = 3; c.weightx = 0.3;
        campoPuerto = new JTextField(String.valueOf(Protocolo.PUERTO), 6);
        panelInferior.add(campoPuerto, c);

        // Fila 2: botones y estado
        c.gridx = 0; c.gridy = 1; c.weightx = 0;
        btnBuscar = new JButton("Buscar");
        panelInferior.add(btnBuscar, c);
        c.gridx = 1; c.weightx = 1;
        lblEstado = new JLabel("Presione Buscar para encontrar servidores.");
        lblEstado.setForeground(Color.GRAY);
        panelInferior.add(lblEstado, c);
        c.gridx = 2; c.gridwidth = 2; c.weightx = 0;
        JButton btnConectar = new JButton("Conectar →");
        btnConectar.setFont(new Font("SansSerif", Font.BOLD, 12));
        panelInferior.add(btnConectar, c);

        panel.add(panelInferior, BorderLayout.SOUTH);

        btnBuscar.addActionListener(e -> buscarServidores());
        btnConectar.addActionListener(e -> intentarConexion());

        return panel;
    }

    // ── Panel de chat ────────────────────────────────────────────────────────

    private JPanel construirPanelChat() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));

        // Etiqueta del servidor actual (arriba)
        lblServidor = new JLabel("Conectado a: —");
        lblServidor.setFont(new Font("SansSerif", Font.ITALIC, 11));
        lblServidor.setForeground(Color.GRAY);
        panel.add(lblServidor, BorderLayout.NORTH);

        // Área de chat: JTextPane para poder insertar botones "Descargar"
        areaChat = new JTextPane();
        areaChat.setEditable(false);
        areaChat.setFont(new Font("Monospaced", Font.PLAIN, 13));
        panel.add(new JScrollPane(areaChat), BorderLayout.CENTER);

        // Panel inferior: campo de texto + botones
        campoMensaje = new JTextField();
        JButton btnEnviar   = new JButton("Enviar");
        JButton btnArchivo  = new JButton("Enviar archivo");
        JButton btnCambiar  = new JButton("Cambiar servidor");
        btnCambiar.setForeground(new Color(180, 60, 60));

        ActionListener accionEnviar = e -> enviarMensaje();
        campoMensaje.addActionListener(accionEnviar);
        btnEnviar.addActionListener(accionEnviar);
        btnArchivo.addActionListener(e -> enviarArchivo());
        btnCambiar.addActionListener(e -> cambiarServidor());

        JPanel panelBotones = new JPanel(new GridLayout(1, 3, 5, 0));
        panelBotones.add(btnEnviar);
        panelBotones.add(btnArchivo);
        panelBotones.add(btnCambiar);

        JPanel panelInferior = new JPanel(new BorderLayout(5, 0));
        panelInferior.add(campoMensaje, BorderLayout.CENTER);
        panelInferior.add(panelBotones, BorderLayout.EAST);
        panel.add(panelInferior, BorderLayout.SOUTH);

        return panel;
    }

    // ─── Descubrimiento UDP ──────────────────────────────────────────────────

    /**
     * Busca servidores en la red local enviando un broadcast UDP.
     * Corre en un hilo separado para no bloquear el EDT durante los 2 segundos
     * de espera de respuestas.
     */
    private void buscarServidores() {
        btnBuscar.setEnabled(false);
        btnBuscar.setText("Buscando…");
        modeloServidores.clear();
        lblEstado.setText("Buscando servidores en la red local…");

        new Thread(() -> {
            List<ServidorInfo> encontrados = descubrirEnRed();

            // Volver al EDT para actualizar la interfaz.
            SwingUtilities.invokeLater(() -> {
                for (ServidorInfo s : encontrados) {
                    modeloServidores.addElement(s);
                }
                lblEstado.setText(encontrados.isEmpty()
                        ? "No se encontraron servidores. Puede conectarse por IP."
                        : encontrados.size() + " servidor(es) encontrado(s).");
                btnBuscar.setEnabled(true);
                btnBuscar.setText("Buscar");
            });
        }).start();
    }

    /**
     * Envía DISCOVER como broadcast UDP y recoge las respuestas SINFO durante 2 segundos.
     * Se ejecuta en un hilo de fondo (no en el EDT).
     */
    private List<ServidorInfo> descubrirEnRed() {
        List<ServidorInfo> encontrados = new ArrayList<>();

        try (DatagramSocket udpSocket = new DatagramSocket()) {
            udpSocket.setBroadcast(true);
            udpSocket.setSoTimeout(2000); // espera máxima de respuestas: 2 segundos

            byte[] datos = Protocolo.DESCUBRIR.getBytes("UTF-8");

            // Enviar a 255.255.255.255 (broadcast general)
            enviarBroadcast(udpSocket, datos, InetAddress.getByName("255.255.255.255"));

            // También enviar al broadcast de cada interfaz de red activa.
            // Esto mejora la compatibilidad en macOS/Linux donde 255.255.255.255
            // a veces no llega a todas las interfaces.
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    if (ni.isLoopback() || !ni.isUp()) continue;
                    for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        InetAddress broadcast = ia.getBroadcast();
                        if (broadcast != null) enviarBroadcast(udpSocket, datos, broadcast);
                    }
                }
            }

            // Recoger todas las respuestas hasta que expire el timeout.
            byte[] buf = new byte[256];
            while (true) {
                try {
                    DatagramPacket respuesta = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(respuesta); // lanza SocketTimeoutException a los 2 seg
                    String contenido = new String(
                            respuesta.getData(), 0, respuesta.getLength(), "UTF-8");
                    String[] partes = Protocolo.parsear(contenido);

                    if (Protocolo.INFO_SERVIDOR.equals(partes[0]) && partes.length >= 3) {
                        String ip     = respuesta.getAddress().getHostAddress();
                        String nombre = partes[1];
                        int    puerto = Integer.parseInt(partes[2]);

                        // Evitar duplicados (puede llegar la misma respuesta por varias interfaces)
                        boolean existe = encontrados.stream()
                                .anyMatch(s -> s.ip.equals(ip) && s.puerto == puerto);
                        if (!existe) {
                            encontrados.add(new ServidorInfo(nombre, ip, puerto));
                        }
                    }
                } catch (SocketTimeoutException e) {
                    break; // Se acabó el tiempo, no hay más respuestas
                }
            }

        } catch (IOException e) {
            // No crítico: si falla el UDP, el usuario puede conectarse manualmente.
        }

        return encontrados;
    }

    private void enviarBroadcast(DatagramSocket socket, byte[] datos, InetAddress destino) {
        try {
            DatagramPacket pkt = new DatagramPacket(
                    datos, datos.length, destino, Protocolo.PUERTO_UDP);
            socket.send(pkt);
        } catch (IOException e) { /* ignorar interfaces sin acceso */ }
    }

    // ─── Conexión TCP ────────────────────────────────────────────────────────

    /** Conecta al servidor seleccionado en la lista o al escrito manualmente. */
    private void intentarConexion() {
        ServidorInfo servidor = listaServidores.getSelectedValue();
        String ip;
        int    puerto;

        if (servidor != null) {
            ip     = servidor.ip;
            puerto = servidor.puerto;
        } else {
            // Conexión manual por IP
            ip = campoIP.getText().trim();
            try {
                puerto = Integer.parseInt(campoPuerto.getText().trim());
            } catch (NumberFormatException e) {
                lblEstado.setText("Puerto inválido.");
                return;
            }
        }

        if (ip.isEmpty()) {
            lblEstado.setText("Ingrese una IP.");
            return;
        }

        conectarA(ip, puerto);
    }

    /**
     * Establece la conexión TCP con el servidor indicado.
     * Limpia el chat, conecta, envía JOIN y lanza el hilo de escucha.
     */
    private void conectarA(String ip, int puerto) {
        // Limpiar el chat y la memoria de archivos del servidor anterior.
        limpiarChat();
        archivosEnMemoria.clear();

        try {
            socketActual = new Socket(ip, puerto);
            entrada = new DataInputStream(socketActual.getInputStream());
            salida  = new DataOutputStream(socketActual.getOutputStream());

            salida.writeUTF(Protocolo.construirJoin(usuario));
            salida.flush();

            // Actualizar la etiqueta del servidor activo.
            String etiqueta = ip + ":" + puerto;
            SwingUtilities.invokeLater(() -> lblServidor.setText("Conectado a: " + etiqueta));

            // Mostrar el panel de chat.
            cardLayout.show(panelRaiz, "CHAT");
            campoMensaje.requestFocusInWindow();

            // Lanzar hilo de escucha para este socket.
            // Por qué un hilo nuevo por cada conexión: el hilo anterior terminó cuando
            // cerramos el socket viejo (lanzó IOException). El nuevo socket necesita
            // su propio hilo de lectura independiente.
            new Thread(this::escucharServidor).start();

        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    "No se pudo conectar a " + ip + ":" + puerto + "\n" + e.getMessage(),
                    "Error de conexión", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ─── Hilo de escucha ────────────────────────────────────────────────────

    /**
     * Corre en hilo separado. Bloquea en readUTF() y despacha cada mensaje.
     * Termina cuando el socket se cierra (IOException).
     */
    private void escucharServidor() {
        try {
            while (true) {
                String linea = entrada.readUTF(); // bloquea aquí
                String tipo  = Protocolo.obtenerTipo(linea);

                switch (tipo) {
                    case Protocolo.FILE:
                        // Leer los bytes del archivo en este hilo para no bloquear el EDT.
                        recibirArchivo(linea);
                        break;
                    case Protocolo.HIST_INI:
                        // El servidor va a enviarnos el historial: mostrar separador.
                        SwingUtilities.invokeLater(() ->
                            agregarTextoEstilizado("── Historial del chat ──\n",
                                    Color.GRAY, Font.ITALIC));
                        break;
                    case Protocolo.HIST_FIN:
                        // Historial terminado; lo que sigue son mensajes en vivo.
                        SwingUtilities.invokeLater(() ->
                            agregarTextoEstilizado("── Mensajes en vivo ──\n",
                                    Color.GRAY, Font.ITALIC));
                        break;
                    default:
                        final String lineaFinal = linea;
                        // Toda actualización de Swing desde un hilo externo DEBE ir
                        // por invokeLater para no tocar componentes fuera del EDT.
                        SwingUtilities.invokeLater(() -> mostrarMensaje(lineaFinal));
                }
            }
        } catch (IOException e) {
            // Si cambiandoServidor == true, cerramos nosotros el socket → no es error.
            if (!cambiandoServidor) {
                SwingUtilities.invokeLater(() ->
                    agregarTexto("\n── Conexión con el servidor perdida. ──\n"));
            }
        }
    }

    // ─── Mostrar mensaje ─────────────────────────────────────────────────────

    private void mostrarMensaje(String linea) {
        String[] partes = Protocolo.parsear(linea);
        if (Protocolo.MSG.equals(partes[0]) && partes.length >= 3) {
            agregarTexto(partes[1] + ": " + partes[2] + "\n");
        }
    }

    // ─── Recibir archivo ─────────────────────────────────────────────────────

    private void recibirArchivo(String encabezado) {
        String[] partes        = Protocolo.parsear(encabezado);
        String   emisor        = partes[1];
        String   nombreArchivo = partes[2];
        long     tamanio       = Long.parseLong(partes[3]);

        try {
            // Bucle de lectura de bytes: igual que en ManejadorCliente.
            byte[] datos = new byte[(int) tamanio];
            int totalLeidos = 0;
            while (totalLeidos < tamanio) {
                int leidos = entrada.read(datos, totalLeidos, (int)(tamanio - totalLeidos));
                if (leidos == -1) throw new IOException("Stream cerrado durante recepción.");
                totalLeidos += leidos;
            }

            // Guardar en memoria (disponible aunque el usuario cambie de servidor y vuelva
            // a conectarse, ya que el servidor reenvía el historial de archivos).
            archivosEnMemoria.put(nombreArchivo, datos);

            final String kb = String.format("%,.1f KB", tamanio / 1024.0);
            SwingUtilities.invokeLater(() -> insertarBotonDescarga(emisor, nombreArchivo, kb));

        } catch (IOException e) {
            SwingUtilities.invokeLater(() ->
                agregarTexto("[Error recibiendo '" + nombreArchivo + "']\n"));
        }
    }

    /** Inserta en el chat el texto del remitente y un botón "⬇ Descargar". */
    private void insertarBotonDescarga(String emisor, String nombreArchivo, String kb) {
        agregarTexto(emisor + " compartió: " + nombreArchivo + " (" + kb + ")  ");

        JButton btnDescargar = new JButton("⬇ Descargar");
        btnDescargar.setFont(new Font("SansSerif", Font.BOLD, 11));
        btnDescargar.setForeground(new Color(0, 100, 200));
        btnDescargar.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnDescargar.setFocusable(false);
        // El botón captura el nombre en el momento en que se crea.
        // Así puede descargarse múltiples veces aunque lleguen otros archivos.
        btnDescargar.addActionListener(e -> guardarArchivo(nombreArchivo));

        areaChat.setCaretPosition(areaChat.getDocument().getLength());
        areaChat.insertComponent(btnDescargar);
        agregarTexto("\n");
    }

    /** Abre JFileChooser para elegir destino y escribe el archivo en disco. */
    private void guardarArchivo(String nombreOriginal) {
        byte[] datos = archivosEnMemoria.get(nombreOriginal);
        if (datos == null) {
            JOptionPane.showMessageDialog(this,
                    "El archivo ya no está en memoria.\nReconéctate al servidor para volver a descargarlo.",
                    "No disponible", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser selector = new JFileChooser();
        selector.setDialogTitle("Guardar archivo como…");
        selector.setSelectedFile(new File(nombreOriginal));
        if (selector.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        try {
            Files.write(selector.getSelectedFile().toPath(), datos);
            agregarTexto("   [guardado en: " + selector.getSelectedFile().getAbsolutePath() + "]\n");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    "Error al guardar: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ─── Enviar mensaje ──────────────────────────────────────────────────────

    private void enviarMensaje() {
        String texto = campoMensaje.getText().trim();
        if (texto.isEmpty() || salida == null || cambiandoServidor) return;
        try {
            salida.writeUTF(Protocolo.construirMsg(usuario, texto));
            salida.flush();
            agregarTexto("Tú: " + texto + "\n");
            campoMensaje.setText("");
        } catch (IOException e) {
            agregarTexto("[Error al enviar]\n");
        }
    }

    // ─── Enviar archivo ──────────────────────────────────────────────────────

    private void enviarArchivo() {
        if (salida == null || cambiandoServidor) return;
        JFileChooser selector = new JFileChooser();
        if (selector.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File archivo = selector.getSelectedFile();
        try {
            byte[] datos = Files.readAllBytes(archivo.toPath());
            // Encabezado primero (writeUTF con longitud), luego bytes crudos.
            salida.writeUTF(Protocolo.construirFile(usuario, archivo.getName(), datos.length));
            salida.flush();
            salida.write(datos, 0, datos.length);
            salida.flush();
            agregarTexto("Tú enviaste: [" + archivo.getName()
                    + ", " + String.format("%,.1f KB", datos.length / 1024.0) + "]\n");
        } catch (IOException e) {
            agregarTexto("[Error al enviar archivo]\n");
        }
    }

    // ─── Cambiar de servidor ─────────────────────────────────────────────────

    /**
     * Desconecta del servidor actual y regresa al panel de descubrimiento.
     * El hilo de escucha termina solo cuando se cierra el socket (lanza IOException).
     */
    private void cambiarServidor() {
        cambiandoServidor = true;

        // Enviar LEAVE para que el servidor notifique a los demás.
        try {
            if (salida != null) {
                salida.writeUTF(Protocolo.construirLeave(usuario));
                salida.flush();
            }
        } catch (IOException e) { /* ignorar */ }

        // Cerrar el socket: esto hace que el hilo de escucha salga de readUTF()
        // con una IOException y termine limpiamente.
        try {
            if (socketActual != null) socketActual.close();
        } catch (IOException e) { /* ignorar */ }

        socketActual = null;
        salida       = null;
        entrada      = null;

        // Limpiar para la próxima sesión.
        archivosEnMemoria.clear();
        limpiarChat();

        // Volver al panel de descubrimiento y buscar servidores automáticamente.
        cardLayout.show(panelRaiz, "DESCUBRIMIENTO");
        cambiandoServidor = false;
        buscarServidores();
    }

    // ─── Utilidades de UI ────────────────────────────────────────────────────

    /** Agrega texto plano al final del JTextPane. Debe llamarse desde el EDT. */
    private void agregarTexto(String texto) {
        try {
            StyledDocument doc = areaChat.getStyledDocument();
            doc.insertString(doc.getLength(), texto, null);
            areaChat.setCaretPosition(doc.getLength());
        } catch (BadLocationException e) { /* no puede ocurrir */ }
    }

    /** Agrega texto con color y estilo (para separadores de historial). */
    private void agregarTextoEstilizado(String texto, Color color, int estilo) {
        try {
            SimpleAttributeSet attrs = new SimpleAttributeSet();
            StyleConstants.setForeground(attrs, color);
            StyleConstants.setItalic(attrs, (estilo & Font.ITALIC) != 0);
            StyleConstants.setBold(attrs,   (estilo & Font.BOLD)   != 0);
            StyledDocument doc = areaChat.getStyledDocument();
            doc.insertString(doc.getLength(), texto, attrs);
            areaChat.setCaretPosition(doc.getLength());
        } catch (BadLocationException e) { /* no puede ocurrir */ }
    }

    /** Borra todo el contenido del área de chat. */
    private void limpiarChat() {
        try {
            areaChat.getStyledDocument().remove(0,
                    areaChat.getStyledDocument().getLength());
        } catch (BadLocationException e) { /* ignorar */ }
    }

    // ─── Cierre de la app ────────────────────────────────────────────────────

    private void cerrar() {
        try {
            if (salida != null) {
                salida.writeUTF(Protocolo.construirLeave(usuario));
                salida.flush();
            }
        } catch (IOException e) { /* ignorar */ }
        System.exit(0);
    }

    // ─── Clase interna: información de un servidor descubierto ───────────────

    static class ServidorInfo {
        final String nombre;
        final String ip;
        final int    puerto;

        ServidorInfo(String nombre, String ip, int puerto) {
            this.nombre = nombre;
            this.ip     = ip;
            this.puerto = puerto;
        }

        @Override
        public String toString() {
            return String.format("%-30s  %s:%d", nombre, ip, puerto);
        }
    }

    // ─── Punto de entrada ────────────────────────────────────────────────────

    public static void main(String[] args) {
        // Toda la creación de Swing en el EDT para evitar condiciones de carrera
        // con el hilo de pintura de la interfaz.
        SwingUtilities.invokeLater(Cliente::new);
    }
}
