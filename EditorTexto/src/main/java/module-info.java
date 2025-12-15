module org.example.editortexto {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.sql;
    requires java.desktop;
    requires vosk;


    opens org.example.editortexto to javafx.fxml;
    exports org.example.editortexto;
}