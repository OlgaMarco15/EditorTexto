package org.example.editortexto;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.text.*;
import javafx.stage.FileChooser;
import org.example.editortexto.nui.NuiCommand;
import org.example.editortexto.nui.NuiController;
import org.example.editortexto.nui.NuiListener;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;


import javax.sound.sampled.*;
import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class controlador implements NuiListener{


    @Override
    public void activarComando(NuiCommand comando, String texto) {
        // Todas las actualizaciones de la UI deben ejecutarse en el hilo de la aplicación de JavaFX.
        Platform.runLater(() -> {
            switch (comando) {
                case negrita:
                    ponerQuitarNegrita();
                    break;
                case cursiva:
                    ponerQuitarCursiva();
                    break;
                case subrayado:
                    ponerQuitarSubrayado();
                    break;
                case mayuscula:
                    alternarMayusMinus();
                    mayuscula = true;
                    break;
                case minuscula:
                    alternarMayusMinus();
                    mayuscula = false;
                    break;
                case invertir:
                    invertirTexto();
                    break;
                case flores_hojas:
                    cambioFlorHoja();
                    break;
                case agua_fuego:
                    cambioAguaFuego();
                    break;
                case pulpo_alien:
                    cambioPulpoAlien();
                    break;
                case quitar_espacios:
                    quitarEspacios();
                    break;
                case exportar:
                    exportarArchivo();
                    break;
                case abrir:
                    abrirArchivo();
                    break;
                case atras:
                    deshacerCambio();
                    break;
                case borrar:
                    limpiarTexto();
                    break;
                case cerrar:
                    cerrarInterfaz();
                    break;
                case dictar:
                    if (texto != null && !texto.isEmpty()) {
                        textArea.appendText(texto + " "); // Añadir el texto dictado al área de texto
                    }
                    break;
            }
        });
    }

    // Clase interna para guardar el estado del TextFlow para el historial de "deshacer".
    private static class EstadoEditor {
        private final List<Node> nodos;

        EstadoEditor(List<Node> nodos) {
            this.nodos = new ArrayList<>();
            // Clona los nodos para que el historial no se vea afectado por cambios posteriores.
            for (Node nodo : nodos) {
                if (nodo instanceof Text) {
                    Text original = (Text) nodo;
                    Text clon = new Text(original.getText());
                    clonarEstilos(original, clon); // Usamos el helper para asegurar la copia completa.
                    this.nodos.add(clon);
                }
            }
        }
        public List<Node> getNodos() {
            return new ArrayList<>(nodos);
        }
    }

    private enum Estilo {
        BOLD, ITALIC, UNDERLINE
    }


    @FXML private TextArea textArea;
    @FXML private TextFlow textFlow;
    @FXML private Label wordsLabel, linesLabel, charsLabel;
    @FXML private ToggleGroup emojiOptions;
    @FXML private Button botonNegrita, botonCursiva, botonSubrayado, botonMayusc, botonInvertir, botonQuitarEspacios;
    @FXML private RadioButton emojiOption1, emojiOption2, emojiOption3;
    @FXML private ProgressLabel progressLabel;


    private final Stack<EstadoEditor> history = new Stack<>();
    private boolean isGlobalBold = false;
    private boolean isGlobalItalic = false;
    private boolean isGlobalUnderline = false;
    // Controla si es la primera modificación para saber si leer del TextArea o del TextFlow.
    private boolean isFirstChange = true;

    private boolean mayuscula = true;

    private volatile boolean dictar = false;

    // Lógica NUI
    private final NuiController nuiController = new NuiController();
    private AudioFormat audioFormat;
    private TargetDataLine microphone;
    private Thread recognizerThread;
    private final AtomicBoolean listening = new AtomicBoolean(false);
    private Model voskModel;

    // Se ejecuta al iniciar la aplicación.
    @FXML
    public void initialize() {
        nuiController.añadirNuiListener(this);
        // Evita que los botones roben el foco y se pierda la selección de texto.

        // Listener para la sincronización en tiempo real al escribir (solo si es el primer cambio).
        textArea.textProperty().addListener((obs, oldVal, newVal) -> {
            if (isFirstChange) {
                pasarTextAreaTestFlow();
            }
            contadorPalabras(newVal);
        });

        textFlow.getChildren().clear(); // El editor empieza vacío.
        historial();
        initializeVosk();
    }

    private void initializeVosk() {
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            URL resource = getClass().getResource("/org/example/editortexto/model/vosk-model-small-es-0.42");
            if (resource == null) {
                System.err.println("No se pudo encontrar el recurso del modelo Vosk. La funcionalidad de voz no estará disponible.");
                return;
            }
            String modelPath = new java.io.File(resource.toURI()).getAbsolutePath();
            voskModel = new Model(modelPath);
        } catch (Exception e) {
            System.err.println("Fallo al inicializar el modelo Vosk. La funcionalidad de voz no estará disponible.");
            e.printStackTrace();
            return;
        }

        audioFormat = new AudioFormat(16000, 16, 1, true, false);
        listening.set(true);


        recognizerThread = new Thread(() -> {
            // Asegurarse de que el modelo se haya cargado antes de usarlo
            if (voskModel == null) {
                System.err.println("El modelo Vosk no está cargado. El hilo de reconocimiento de voz terminará.");
                return;
            }

            // Prepara la lista de palabras para la gramática del reconocedor.
            // Esto aumenta mucho la precisión al limitar el vocabulario que Vosk debe buscar.
            String grammar = "[\"negrita\", \"cursiva\", \"subrayado\", \"nuevo\", \"limpiar\", \"abrir\", \"cargar\", \"guardar\", \"exportar\", \"mayúscula\", \"minúscula\", \"invierte\", \"invertir\", \"quitar\", \"espacios\", \"flores\", \"hojas\", \"agua\", \"fuego\", \"pulpo\", \"alien\", \"atrás\", \"deshacer\", \"cerrar\", \"[unk]\"]";

            try {
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, audioFormat);

                if (!AudioSystem.isLineSupported(info)) {
                    System.err.println("CRÍTICO: El sistema no soporta el formato de audio requerido para el micrófono.");
                    return;
                }

                microphone = (TargetDataLine) AudioSystem.getLine(info);
                microphone.open(audioFormat);
                microphone.start();

                while (listening.get()) {
                    boolean modoDictado = dictar;
                    try (Recognizer recognizer = modoDictado ? new Recognizer(voskModel, 16000) : new Recognizer(voskModel, 16000, grammar)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;

                        while (listening.get() && dictar == modoDictado) {
                            try {

                                bytesRead = microphone.read(buffer, 0, buffer.length);

                            } catch (Exception readException) {
                                break;
                            }

                            if (bytesRead < 0) {
                                break;
                            }

                            if (recognizer.acceptWaveForm(buffer, bytesRead)) {
                                String resultJson = recognizer.getResult();
                                System.out.println(resultJson);
                                String recognizedText = parseRecognizedText(resultJson, "text");

                                if (recognizedText != null && !recognizedText.isEmpty()) {
                                    processVoiceCommand(recognizedText);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    System.err.println("CRÍTICO: Fallo al inicializar el micrófono. Es posible que no haya ningún micrófono disponible o que esté siendo usado por otra aplicación.");
                    e.printStackTrace();
                });
                return; // Ensure the thread terminates on microphone initialization failure
            } finally {

                if (microphone != null && microphone.isOpen()) {
                    microphone.stop();
                    microphone.close();
                }

            }
        }, "Vosk-Recognizer-Thread");

        recognizerThread.setDaemon(true);
        recognizerThread.start();
    }

    private String parseRecognizedText(String json, String key) {
        // Usar una expresión regular para ser flexible con los espacios en el JSON.
        // Busca patrones como: "key" : "value"
        Pattern pattern = Pattern.compile("\"" + key + "\"\s*:\s*\"(.*?)\"");
        Matcher matcher = pattern.matcher(json);

        if (matcher.find()) {
            return matcher.group(1).trim(); // group(1) es el texto capturado por (.*?)
        }

        return ""; // Devolver vacío si no se encuentra el patrón.
    }



private void processVoiceCommand(String commandText) {
    String lowerCaseCommand = commandText.toLowerCase();
    NuiCommand command = NuiCommand.comando_no_reconocido;
    String texto = null;

    if (dictar) {
        command = NuiCommand.dictar;
        texto = commandText;
    } else if (lowerCaseCommand.contains("negrita")) {
        command = NuiCommand.negrita;
    } else if (lowerCaseCommand.contains("cursiva")) {
        command = NuiCommand.cursiva;
    } else if (lowerCaseCommand.contains("subrayado")) {
        command = NuiCommand.subrayado;
    } else if (lowerCaseCommand.contains("nuevo") || lowerCaseCommand.contains("limpiar")) {
        command = NuiCommand.borrar;
    } else if (lowerCaseCommand.contains("abrir") || lowerCaseCommand.contains("cargar")) {
        command = NuiCommand.abrir;
    } else if (lowerCaseCommand.contains("guardar") || lowerCaseCommand.contains("exportar")) {
        command = NuiCommand.exportar;
    } else if (lowerCaseCommand.contains("mayúscula")) {
        mayuscula = false;
        command = NuiCommand.mayuscula;
    } else if (lowerCaseCommand.contains("minúscula")) {
        mayuscula = true;
        command = NuiCommand.minuscula;
    } else if (lowerCaseCommand.contains("invierte") || lowerCaseCommand.contains("invertir")) {
        command = NuiCommand.invertir;
    } else if (lowerCaseCommand.contains("quitar espacios")) {
        command = NuiCommand.quitar_espacios;
    } else if (lowerCaseCommand.contains("flores") || lowerCaseCommand.contains("hojas")) {
        command = NuiCommand.flores_hojas;
    } else if (lowerCaseCommand.contains("agua") || lowerCaseCommand.contains("fuego")) {
        command = NuiCommand.agua_fuego;
    } else if (lowerCaseCommand.contains("pulpo") || lowerCaseCommand.contains("alien")) {
        command = NuiCommand.pulpo_alien;
    } else if (lowerCaseCommand.contains("atrás") || lowerCaseCommand.contains("deshacer")) {
        command = NuiCommand.atras;
    } else if (lowerCaseCommand.contains("cerrar")) {
        command = NuiCommand.cerrar;
    }

    NuiController.procesarComando(command, texto);
}



    private void pasarTextAreaTestFlow() {
        textFlow.getChildren().clear();
        Text textoCompleto = new Text(textArea.getText());
        aplicarEstilosGlobales(textoCompleto);
        textFlow.getChildren().add(textoCompleto);
    }


    private void aplicarEstilosGlobales(Text textNode) {
        FontWeight weight = isGlobalBold ? FontWeight.BOLD : FontWeight.NORMAL;
        FontPosture posture = isGlobalItalic ? FontPosture.ITALIC : FontPosture.REGULAR;
        textNode.setFont(Font.font("System", weight, posture, 12));
        textNode.setUnderline(isGlobalUnderline);
    }

    private void historial() {
        history.push(new EstadoEditor(textFlow.getChildren()));
    }

    private String pillarTextoTextFlow() {
        return textFlow.getChildren().stream()
                .filter(node -> node instanceof Text)
                .map(node -> ((Text) node).getText())
                .collect(Collectors.joining());
    }


    @FXML
    protected void deshacerCambio() {
        if (history.size() > 1) {
            history.pop();
            EstadoEditor estadoAnterior = history.peek();
            textFlow.getChildren().setAll(estadoAnterior.getNodos());
            // Sincroniza el textArea para que las futuras selecciones sean correctas.
            Platform.runLater(() -> {
                textArea.setText(pillarTextoTextFlow());
                isFirstChange = textFlow.getChildren().isEmpty() || (textFlow.getChildren().size() == 1 && ((Text)textFlow.getChildren().get(0)).getText().isEmpty());
            });
        }
    }

    private static void clonarEstilos(Text origen, Text destino) {
        destino.setFont(origen.getFont());
        destino.setUnderline(origen.isUnderline());
        destino.setFill(origen.getFill());
    }

    private Text clonarNodoTexto(Text original) {
        Text clon = new Text(original.getText());
        clonarEstilos(original, clon);
        return clon;
    }

    private void aplicarNuevoEstilo(Text nodo, Estilo estilo) {
        Font fontActual = nodo.getFont();
        String style = fontActual.getStyle();
        FontWeight nuevoWeight = style.contains("Bold") ? FontWeight.BOLD : FontWeight.NORMAL;
        FontPosture nuevoPosture = style.contains("Italic") ? FontPosture.ITALIC : FontPosture.REGULAR;
        boolean esSubrayado = nodo.isUnderline();

        switch (estilo) {
            case BOLD:
                nuevoWeight = (nuevoWeight == FontWeight.BOLD) ? FontWeight.NORMAL : FontWeight.BOLD;
                break;
            case ITALIC:
                nuevoPosture = (nuevoPosture == FontPosture.ITALIC) ? FontPosture.REGULAR : FontPosture.ITALIC;
                break;
            case UNDERLINE:
                esSubrayado = !esSubrayado;
                break;
        }

        nodo.setFont(Font.font(fontActual.getFamily(), nuevoWeight, nuevoPosture, fontActual.getSize()));
        nodo.setUnderline(esSubrayado);
    }

    private void aplicarEstiloEnSeleccion(IndexRange selection, Estilo estilo) {
        isGlobalBold = false; isGlobalItalic = false; isGlobalUnderline = false;
        List<Node> nuevosNodos = new ArrayList<>();
        int posActual = 0;
        final int selStart = selection.getStart();
        final int selEnd = selection.getEnd();
        List<Node> currentNodes = new ArrayList<>(textFlow.getChildren());

        for (Node nodo : currentNodes) {
            if (!(nodo instanceof Text)) {
                nuevosNodos.add(nodo);
                continue;
            }
            Text textoActual = (Text) nodo;
            int len = textoActual.getText().length();
            int nodoStart = posActual;
            int nodoEnd = posActual + len;

            if (nodoEnd <= selStart || nodoStart >= selEnd) {
                nuevosNodos.add(clonarNodoTexto(textoActual));
            } else if (nodoStart >= selStart && nodoEnd <= selEnd) {
                Text styledNode = clonarNodoTexto(textoActual);
                aplicarNuevoEstilo(styledNode, estilo);
                nuevosNodos.add(styledNode);
            } else {
                if (nodoStart < selStart) {
                    String beforeText = textoActual.getText().substring(0, selStart - nodoStart);
                    Text beforeNode = new Text(beforeText);
                    clonarEstilos(textoActual, beforeNode);
                    nuevosNodos.add(beforeNode);
                }
                int startCut = Math.max(nodoStart, selStart);
                int endCut = Math.min(nodoEnd, selEnd);
                String middleText = textoActual.getText().substring(startCut - nodoStart, endCut - nodoStart);
                Text middleNode = new Text(middleText);
                clonarEstilos(textoActual, middleNode);
                aplicarNuevoEstilo(middleNode, estilo);
                nuevosNodos.add(middleNode);
                if (nodoEnd > selEnd) {
                    String afterText = textoActual.getText().substring(selEnd - nodoStart);
                    Text afterNode = new Text(afterText);
                    clonarEstilos(textoActual, afterNode);
                    nuevosNodos.add(afterNode);
                }
            }
            posActual += len;
        }
        actualizarTextFlowYTextArea(nuevosNodos, selStart, selEnd);
    }

    private void aplicarTransformacionEnSeleccion(IndexRange selection, Function<String, String> transformer) {
        List<Node> nuevosNodos = new ArrayList<>();
        int posActual = 0;
        final int selStart = selection.getStart();
        final int selEnd = selection.getEnd();
        List<Node> currentNodes = new ArrayList<>(textFlow.getChildren());

        for (Node nodo : currentNodes) {
            if (!(nodo instanceof Text)) {
                nuevosNodos.add(nodo);
                continue;
            }
            Text textoActual = (Text) nodo;
            int len = textoActual.getText().length();
            int nodoStart = posActual;
            int nodoEnd = posActual + len;

            if (nodoEnd <= selStart || nodoStart >= selEnd) {
                nuevosNodos.add(clonarNodoTexto(textoActual));
            } else if (nodoStart >= selStart && nodoEnd <= selEnd) {
                Text transformedNode = clonarNodoTexto(textoActual);
                transformedNode.setText(transformer.apply(transformedNode.getText()));
                nuevosNodos.add(transformedNode);
            } else {
                if (nodoStart < selStart) {
                    String beforeText = textoActual.getText().substring(0, selStart - nodoStart);
                    Text beforeNode = new Text(beforeText);
                    clonarEstilos(textoActual, beforeNode);
                    nuevosNodos.add(beforeNode);
                }
                int startCut = Math.max(nodoStart, selStart);
                int endCut = Math.min(nodoEnd, selEnd);
                String middleText = textoActual.getText().substring(startCut - nodoStart, endCut - nodoStart);
                Text middleNode = new Text(transformer.apply(middleText));
                clonarEstilos(textoActual, middleNode);
                nuevosNodos.add(middleNode);
                if (nodoEnd > selEnd) {
                    String afterText = textoActual.getText().substring(selEnd - nodoStart);
                    Text afterNode = new Text(afterText);
                    clonarEstilos(textoActual, afterNode);
                    nuevosNodos.add(afterNode);
                }
            }
            posActual += len;
        }
        actualizarTextFlowYTextArea(nuevosNodos, selStart, selEnd);
    }

    private void actualizarTextFlowYTextArea(List<Node> nuevosNodos, int selStart, int selEnd) {
        textFlow.getChildren().setAll(nuevosNodos);
        Platform.runLater(() -> {
            textArea.setText(pillarTextoTextFlow());
            textArea.selectRange(selStart, selEnd);
        });
    }


    @FXML
    protected void ponerQuitarNegrita() {
        IndexRange selection = textArea.getSelection();
        if (selection.getLength() == 0) {
            isGlobalBold = !isGlobalBold;
            String baseText = isFirstChange ? textArea.getText() : pillarTextoTextFlow();
            textFlow.getChildren().clear();
            Text newNode = new Text(baseText);
            aplicarEstilosGlobales(newNode);
            textFlow.getChildren().add(newNode);
        } else {
            aplicarEstiloEnSeleccion(selection, Estilo.BOLD);
        }
        isFirstChange = false;
        historial();
    }

    @FXML
    protected void ponerQuitarCursiva() {
        IndexRange selection = textArea.getSelection();
        if (selection.getLength() == 0) {
            isGlobalItalic = !isGlobalItalic;
            String baseText = isFirstChange ? textArea.getText() : pillarTextoTextFlow();
            textFlow.getChildren().clear();
            Text newNode = new Text(baseText);
            aplicarEstilosGlobales(newNode);
            textFlow.getChildren().add(newNode);
        } else {
            aplicarEstiloEnSeleccion(selection, Estilo.ITALIC);
        }
        isFirstChange = false;
        historial();
    }

    @FXML
    protected void ponerQuitarSubrayado() {
        IndexRange selection = textArea.getSelection();
        if (selection.getLength() == 0) {
            isGlobalUnderline = !isGlobalUnderline;
            String baseText = isFirstChange ? textArea.getText() : pillarTextoTextFlow();
            textFlow.getChildren().clear();
            Text newNode = new Text(baseText);
            aplicarEstilosGlobales(newNode);
            textFlow.getChildren().add(newNode);
        } else {
            aplicarEstiloEnSeleccion(selection, Estilo.UNDERLINE);
        }
        isFirstChange = false;
        historial();
    }

    @FXML
    protected void alternarMayusMinus() {
        IndexRange selection = textArea.getSelection();
        mayuscula =! mayuscula;
        Function<String, String> transformer = texto -> {

            if (mayuscula) {

                return texto.toLowerCase();
            } else {
                return texto.toUpperCase();
            }

        };

        if (selection.getLength() == 0) {
            String textoAProcesar = isFirstChange ? textArea.getText() : pillarTextoTextFlow();
            Text nodoTransformado = new Text(transformer.apply(textoAProcesar));
            textFlow.getChildren().clear();
            aplicarEstilosGlobales(nodoTransformado);
            textFlow.getChildren().add(nodoTransformado);
        } else {
            aplicarTransformacionEnSeleccion(selection, transformer);
        }
        isFirstChange = false;
        historial();
    }

    @FXML
    protected void invertirTexto() {
        IndexRange selection = textArea.getSelection();
        Function<String, String> transformer = texto -> new StringBuilder(texto).reverse().toString();

        if (selection.getLength() == 0) {
            String textoAProcesar = isFirstChange ? textArea.getText() : pillarTextoTextFlow();
            Text nodoTransformado = new Text(transformer.apply(textoAProcesar));
            textFlow.getChildren().clear();
            aplicarEstilosGlobales(nodoTransformado);
            textFlow.getChildren().add(nodoTransformado);
        } else {
            aplicarTransformacionEnSeleccion(selection, transformer);
        }
        isFirstChange = false;
        historial();
    }

    @FXML
    protected void quitarEspacios() {
        IndexRange selection = textArea.getSelection();
        Function<String, String> transformer = texto -> texto.trim().replaceAll("\\s+", " ");

        if (selection.getLength() == 0) {
            String textoAProcesar = isFirstChange ? textArea.getText() : pillarTextoTextFlow();
            Text nodoTransformado = new Text(transformer.apply(textoAProcesar));
            textFlow.getChildren().clear();
            aplicarEstilosGlobales(nodoTransformado);
            textFlow.getChildren().add(nodoTransformado);
        } else {
            aplicarTransformacionEnSeleccion(selection, transformer);
        }
        isFirstChange = false;
        historial();
    }

    @FXML
    protected void limpiarTexto() {
        textArea.clear();
        textFlow.getChildren().clear();
        isFirstChange = true;
        isGlobalBold = false;
        isGlobalItalic = false;
        isGlobalUnderline = false;
        historial();
    }

    private void contadorPalabras(String text) {
        String[] words = text.trim().split("\\s+");
        wordsLabel.setText(text.trim().isEmpty() ? "0" : String.valueOf(words.length));
        linesLabel.setText(String.valueOf(text.split("\\r?\\n").length));
        charsLabel.setText(String.valueOf(text.length()));
    }

    @FXML protected void cambioFlorHoja() { emojisPorLetras("🌸", "🌿"); }
    @FXML protected void cambioAguaFuego() { emojisPorLetras("💧", "🔥"); }
    @FXML protected void cambioPulpoAlien() { emojisPorLetras("🐙", "👽"); }

    private void emojisPorLetras(String vowelEmoji, String consonantEmoji) {
        String textoOriginal = isFirstChange ? textArea.getText() : pillarTextoTextFlow();
        StringBuilder newTextBuilder = new StringBuilder();
        for (char c : textoOriginal.toCharArray()) {
            String charStr = String.valueOf(c);
            if ("aeiouAEIOU".contains(charStr)) { newTextBuilder.append(vowelEmoji); 
            } else if ("bcdfghjklmnpqrstvwxyzBCDFGHJKLMNPQRSTVWXYZ".contains(charStr)) { newTextBuilder.append(consonantEmoji); 
            } else { newTextBuilder.append(c); }
        }
        textFlow.getChildren().clear();
        textFlow.getChildren().add(new Text(newTextBuilder.toString()));
        isFirstChange = false;
        historial();
    }

    @FXML
    private void exportarArchivo() {
        String contenidoASlavar = convertirTextFlowAMarkdown(); // Llama al convertidor a Markdown. //TODO

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Guardar como...");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Archivo de texto", "*.md"));
        File archivo = fileChooser.showSaveDialog(null);
        if (archivo != null) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(archivo))) {
                writer.write(contenidoASlavar);
            } catch (IOException e) { e.printStackTrace(); }
        }

        Thread hilo = new Thread(() -> {
            try{
                for (int i = 1; i <= 5; i++) {
                    Thread.sleep(1000);
                    final int step = i;
                    Platform.runLater(() -> {
                        progressLabel.setProgress(step / 5.0);
                        progressLabel.setText("Guardando... " + (step * 20) + "%");
                    });
                }
            }catch(InterruptedException e){
                e.printStackTrace();
            }
        });

        hilo.start();
    } //TODO: dentro

    @FXML
    private void abrirArchivo() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Abrir archivo de texto");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Archivo de texto", "*.md"));
        File archivo = fileChooser.showOpenDialog(null);
        if (archivo != null) {
            try (BufferedReader reader = new BufferedReader(new FileReader(archivo))) {
                String contenido = reader.lines().collect(Collectors.joining("\n"));

                textArea.setText(contenido);
                convertirMarkdownATextFlow(contenido); //TODO:
                isFirstChange = false;
                isGlobalBold = false; isGlobalItalic = false; isGlobalUnderline = false;
                historial();
            } catch (IOException e) { e.printStackTrace(); }
        }
    }//TODO: dentro

    // TODO: --- LÓGICA DE MARKDOWN (Métodos que añadí en el examen) ---

    //TODO: Convierte el estado actual del TextFlow a una cadena de texto en formato Markdown
    private String convertirTextFlowAMarkdown() {
        StringBuilder markdownBuilder = new StringBuilder(); // StringBuilder para añadir el texto
        for (Node node : textFlow.getChildren()) { // Itera sobre cada nodo (fragmento de texto) del TextFlow
            if (node instanceof Text) { // Si el nodo es de tipo texto
                Text textNode = (Text) node; // lo convierte a la clase Text
                String texto = textNode.getText(); // Obtiene el contenido de texto plano del nodo

                // Evita añadir delimitadores Markdown a texto vacío o que solo contiene espacios
                if (texto.trim().isEmpty()) { // Si el texto está vacío o es solo espacio
                    markdownBuilder.append(texto); // lo añade tal cual
                    continue; // y pasa al siguiente nodo
                }

                String estilo = textNode.getFont().getStyle().toLowerCase(); // Obtiene el estilo de la fuente en minúsculas
                boolean esNegrita = estilo.contains("bold"); // Comprueba si el estilo contiene negrita
                boolean esCursiva = estilo.contains("italic"); // Comprueba si el estilo contiene cursiva
                boolean esSubrayado = textNode.isUnderline(); // Comprueba si el texto está subrayado

                String textoReadme = texto; // Inicializa el texto Markdown con el texto plano

                if (esNegrita && esCursiva) { // Si es negrita y cursiva
                    textoReadme = "***" + textoReadme + "***"; // lo pone con tres asteriscos
                } else if (esNegrita) { // Si es solo negrita,
                    textoReadme = "**" + textoReadme + "**"; // lo pone con dos asteriscos
                } else if (esCursiva) { // Si es solo cursiva,
                    textoReadme = "*" + textoReadme + "*"; // lo pone con un asterisco
                }

                if (esSubrayado) {
                    textoReadme = "<u>" + textoReadme + "</u>"; // lo envuelve con etiquetas HTML de subrayado
                }
                markdownBuilder.append(textoReadme); // Añade el texto con formato Markdown al resultado final
            }
        }
        return markdownBuilder.toString(); // Devuelve el readme.
    }

    private void convertirMarkdownATextFlow(String markdown) {
        // Limpia el contenido actual del TextFlow para empezar de cero (Porque si hay texto ya, se mezcla con el texto que ya hay)
        textFlow.getChildren().clear();



        // Patrón para encontrar texto envuelto en etiquetas <u></u>.

        Pattern patronSubrayado = Pattern.compile("<u>(.*?)</u>", Pattern.DOTALL);
        Matcher matcherSubrayado = patronSubrayado.matcher(markdown);

        int ultimaPosicion = 0; // Para rastrear qué partes del texto ya se han procesado.

        // Itera sobre todas las coincidencias de texto subrayado.
        while (matcherSubrayado.find()) {
            // Procesa el texto que está ANTES de la sección subrayada.
            // Este texto, por definición, no está subrayado.
            if (matcherSubrayado.start() > ultimaPosicion) {
                String textoPrevio = markdown.substring(ultimaPosicion, matcherSubrayado.start());
                procesarEstilosNegritaCursiva(textoPrevio, false); // false porque no está subrayado.
            }

            // Procesa el texto que está DENTRO de las etiquetas <u></u>.
            String textoSubrayado = matcherSubrayado.group(1); // group(1) es el contenido entre las etiquetas.
            procesarEstilosNegritaCursiva(textoSubrayado, true); // true porque sí está subrayado.

            // Actualiza la última posición procesada.
            ultimaPosicion = matcherSubrayado.end();
        }

        // Procesa el texto restante que queda DESPUÉS de la última sección subrayada.
        // Este texto tampoco está subrayado.
        if (ultimaPosicion < markdown.length()) {
            String textoRestante = markdown.substring(ultimaPosicion);
            procesarEstilosNegritaCursiva(textoRestante, false);
        }
    }

    private void procesarEstilosNegritaCursiva(String texto, boolean esSubrayado) {
        // Patrón para identificar negrita-cursiva (***), negrita (**), y cursiva (*).
        // El orden es importante para que no confunda *** con ** o *.
                final Pattern patronEstilos = Pattern.compile("(\\*\\*\\*(.*?)\\*\\*\\*)|(\\*\\*(.*?)\\*\\*)|(\\*([^*]+?)\\*)");
        Matcher matcherEstilos = patronEstilos.matcher(texto);
        int ultimaPosicion = 0; // Rastrea la posición en el fragmento de texto actual.

        // Itera sobre todas las coincidencias de estilos.
        while (matcherEstilos.find()) {
            // Añade el texto que se encuentra antes del siguiente estilo.
            if (matcherEstilos.start() > ultimaPosicion) {
                String textoSinEstilo = texto.substring(ultimaPosicion, matcherEstilos.start());
                añadirNodosTexto(textoSinEstilo, false, false, esSubrayado);
            }

            String contenido;
            boolean esNegrita = false;
            boolean esCursiva = false;

            if (matcherEstilos.group(1) != null) { // Coincidencia de ***...***
                contenido = matcherEstilos.group(2);
                esNegrita = true;
                esCursiva = true;
            } else if (matcherEstilos.group(3) != null) { // Coincidencia de **...**
                contenido = matcherEstilos.group(4);
                esNegrita = true;
            } else { // Coincidencia de *...*
                contenido = matcherEstilos.group(6);
                esCursiva = true;
            }

            // Añade el texto con los estilos correspondientes.
            añadirNodosTexto(contenido, esNegrita, esCursiva, esSubrayado);
            ultimaPosicion = matcherEstilos.end(); // Actualiza la posición.
        }

        // Añade el texto restante que no tenía ningún estilo de negrita/cursiva.
        if (ultimaPosicion < texto.length()) {
            String textoRestante = texto.substring(ultimaPosicion);
            añadirNodosTexto(textoRestante, false, false, esSubrayado);
        }
    }

    private void añadirNodosTexto(String content, boolean negrita, boolean cursiva, boolean subrayado) {
        if (content.isEmpty()) return;

        String[] lines = content.split("\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isEmpty()) {
                Text textNode = new Text(lines[i]);
                FontWeight weight = negrita ? FontWeight.BOLD : FontWeight.NORMAL;
                FontPosture posture = cursiva ? FontPosture.ITALIC : FontPosture.REGULAR;
                textNode.setFont(Font.font("System", weight, posture, 12));
                textNode.setUnderline(subrayado);
                textFlow.getChildren().add(textNode);
            }

            if (i < lines.length - 1) {
                textFlow.getChildren().add(new Text("\n"));
            }
        }
    }


    @FXML
    private void dictarTexto(ActionEvent actionEvent) {
        dictar =!dictar;
    }



    @FXML protected void cerrarInterfaz() { Platform.exit(); }
}