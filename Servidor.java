/**
 * Servidor.java
 * ─────────────────────────────────────────────────────────────────────────────
 * COMPILAR :  javac *.java
 * EJECUTAR :  java Servidor [nombre]     ← nombre es opcional
 *             java Cliente              ← una terminal por cada cliente
 *
 * Ejemplos:
 *   java Servidor                       → nombre automático (hostname)
 *   java Servidor "Sala de estudio"     → nombre personalizado
 *
 * El servidor hace DOS cosas en paralelo:
 *   1. Hilo UDP  → responde solicitudes de descubrimiento de la red local.
 *   2. Hilo TCP  → acepta conexiones de chat y lanza un ManejadorCliente por cada una.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import java.io.IOException;
import java.net.*;
import java.util.*;

public class Servidor {

    public static void main(String[] args) {

        // Nombre del servidor: primer argumento o "Servidor@hostname" por defecto.
        String nombre;
        try {
            nombre = (args.length > 0)
                    ? args[0]
                    : "Servidor@" + InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            nombre = "Servidor";
        }

        // Historial compartido entre todos los manejadores.
        // Cuando un cliente se une, recibe los mensajes anteriores desde aquí.
        Historial historial = new Historial();

        // Lista sincronizada de clientes activos para hacer broadcast.
        List<ManejadorCliente> clientes =
                Collections.synchronizedList(new ArrayList<>());

        // ── Hilo UDP: descubrimiento en la red local ──────────────────────────
        // Por qué un hilo separado: DatagramSocket.receive() bloquea igual que
        // ServerSocket.accept(). Si lo corriéramos en el hilo principal, el servidor
        // quedaría esperando solicitudes UDP y nunca aceptaría conexiones TCP.
        iniciarHiloUDP(nombre, clientes);

        // ── Hilo TCP principal: acepta conexiones de chat ─────────────────────
        try (ServerSocket serverSocket = new ServerSocket(Protocolo.PUERTO)) {

            System.out.println("══════════════════════════════════════════════");
            System.out.println("  Servidor de chat: " + nombre);
            System.out.println("  Puerto TCP : " + Protocolo.PUERTO);
            System.out.println("  Puerto UDP : " + Protocolo.PUERTO_UDP + " (descubrimiento)");
            System.out.println("══════════════════════════════════════════════");

            while (true) {
                // accept() bloquea hasta que llega una nueva conexión TCP.
                Socket socket = serverSocket.accept();
                System.out.println("[TCP] Nueva conexión desde: "
                        + socket.getInetAddress().getHostAddress());

                // Cada cliente recibe su propio hilo para no bloquear la aceptación.
                ManejadorCliente manejador =
                        new ManejadorCliente(socket, clientes, historial);
                new Thread(manejador).start();
            }

        } catch (IOException e) {
            System.err.println("[ERROR] No se pudo iniciar el servidor: " + e.getMessage());
        }
    }

    /**
     * Lanza el hilo de descubrimiento UDP.
     * Escucha en PUERTO_UDP por mensajes "DISCOVER" y responde con la info del servidor.
     *
     * Es un hilo daemon: se detiene automáticamente cuando el proceso del servidor termina.
     */
    private static void iniciarHiloUDP(String nombre, List<ManejadorCliente> clientes) {
        Thread hiloUDP = new Thread(() -> {
            // DatagramSocket: socket UDP. A diferencia de TCP, no hay conexión:
            // cada datagrama es independiente (como enviar cartas, no llamadas telefónicas).
            try (DatagramSocket udpSocket = new DatagramSocket(Protocolo.PUERTO_UDP)) {

                System.out.println("[UDP] Descubrimiento activo en puerto " + Protocolo.PUERTO_UDP);
                byte[] buf = new byte[256];

                while (true) {
                    // Esperar la llegada de cualquier datagrama UDP.
                    DatagramPacket solicitud = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(solicitud); // bloquea hasta que llega algo

                    String contenido = new String(
                            solicitud.getData(), 0, solicitud.getLength(), "UTF-8").trim();

                    // Solo responder a mensajes DISCOVER para evitar basura de red.
                    if (Protocolo.DESCUBRIR.equals(contenido)) {
                        String respuesta = Protocolo.construirInfoServidor(nombre, Protocolo.PUERTO);
                        byte[] respBytes = respuesta.getBytes("UTF-8");

                        // Responder directamente al remitente (no es broadcast).
                        DatagramPacket paqueteResp = new DatagramPacket(
                                respBytes, respBytes.length,
                                solicitud.getAddress(), solicitud.getPort());
                        udpSocket.send(paqueteResp);

                        System.out.println("[UDP] Respondido a " +
                                solicitud.getAddress().getHostAddress());
                    }
                }

            } catch (IOException e) {
                // Si el puerto UDP ya está en uso (otro servidor en la misma máquina),
                // el servidor sigue funcionando; solo no será descubrible automáticamente.
                System.err.println("[UDP] No se pudo iniciar el descubrimiento: " + e.getMessage());
                System.err.println("[UDP] Los clientes deberán conectarse manualmente por IP.");
            }
        });

        hiloUDP.setDaemon(true); // termina con la JVM, no impide el cierre del servidor
        hiloUDP.start();
    }
}
