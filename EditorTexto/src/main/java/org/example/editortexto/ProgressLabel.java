package org.example.editortexto;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;

// Componente visual que une una barra de progreso y una etiqueta de texto.
public class ProgressLabel extends VBox {
    // La barra de progreso visual.
    private final ProgressBar progressBar;
    // El texto que se muestra junto a la barra.
    private final Label label;

    // Constructor por defecto: progreso a 0 y texto "Listo".
    public ProgressLabel() {
        this(0.0, "Listo");
    }

    // Constructor principal que inicializa la barra y el texto.
    public ProgressLabel(double progress, String text) {
        this.progressBar = new ProgressBar(progress);
        this.label = new Label(text);

        // Espacio vertical entre la barra y la etiqueta.
        setSpacing(4);
        // Ancho preferido para la barra de progreso.
        progressBar.setPrefWidth(200);

        // Añade la barra y la etiqueta a este componente VBox.
        getChildren().addAll(progressBar, label);
    }

    // --- Métodos para interactuar con el componente ---

    // Actualiza el valor de la barra de progreso (de 0.0 a 1.0).
    public void setProgress(double value) {
        progressBar.setProgress(value);
    }

    // Devuelve el valor actual del progreso.
    public double getProgress() {
        return progressBar.getProgress();
    }

    // Permite enlazar (binding) el progreso a otras partes de la aplicación.
    public DoubleProperty progressProperty() {
        return progressBar.progressProperty();
    }

    // Actualiza el texto de la etiqueta.
    public void setText(String text) {
        label.setText(text);
    }

    // Devuelve el texto actual de la etiqueta.
    public String getText() {
        return label.getText();
    }

    // Permite enlazar (binding) el texto a otras propiedades.
    public StringProperty textProperty() {
        return label.textProperty();
    }
}