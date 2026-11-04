package dev.voxelbrew.gui;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.mesh.MeshData;
import dev.voxelbrew.engine.export.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Point3D;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** CAD workbench: model state stays in engine; scene graph and tool previews stay here. */
public final class Workbench extends BorderPane implements AutoCloseable {
    private record Entry(String id,String label,boolean body) { @Override public String toString(){return label;} }
    private enum Tool { NONE, RECTANGLE, CIRCLE, EXTRUDE }
    private final Stage stage;
    private final DocumentController controller=new DocumentController(Platform::runLater);
    private final ExporterRegistry registry;
    private final Viewport viewport=new Viewport();
    private final TreeView<Entry> tree=new TreeView<>();
    private final VBox inspector=new VBox(12),form=new VBox(9);
    private final VBox featureDiagnostics=new VBox(6);
    private final HBox toolActions=new HBox(8);
    private final Label status=new Label("Ready"),error=new Label(),selectionLabel=new Label("No selection"),cursorLabel=new Label();
    private final Slider cursor=new Slider();
    private final ComboBox<String> views=new ComboBox<>();
    private final HBox history=new HBox(6);
    private final Button undo=button("Undo","undo",()->{cancelTool();controller.undo();}),redo=button("Redo","redo",()->{cancelTool();controller.redo();});
    private final Button export=button("Export…","export",()->ExportDialog.show(stage(),controller,registry()));
    private final Button rectangle=button("▭  Rectangle","rectangle",()->beginSketch(false));
    private final Button circle=button("○  Circle","circle",()->beginSketch(true));
    private final Button extrude=button("↑  Extrude","extrude",this::beginExtrusion);
    private final Button cut=button("Through cut","cut",this::beginCut);
    private final ProgressIndicator progress=new ProgressIndicator();
    private final Appearance appearance=new Appearance();
    private final ExecutorService preferencesWorker=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("voxelbrew-appearance").factory());
    private Appearance.Choice appearanceChoice;
    private long appearanceRequest;
    private boolean closed,refreshing;
    private Tool tool=Tool.NONE;
    private String selectedFeature,selectedBody;
    private final Map<String,TextField> fields=new LinkedHashMap<>();
    private List<Feature> lastFeatures=List.of();
    private DocumentController.Build lastBuild;
    private String lastDocument="";
    private boolean fitNext=true;
    private Point3D firstPoint;
    private Feature.Sketch extrusionSketch;
    private Button finish;
    public Workbench(Stage stage,Path pluginDirectory) throws IOException {
        this.stage=stage;this.registry=new ExporterRegistry(pluginDirectory);appearanceChoice=appearance.load();
        setId("workbench");featureDiagnostics.setId("feature-diagnostics");getStylesheets().add(Objects.requireNonNull(getClass().getResource("workbench.css")).toExternalForm());
        var brand=new Label("VOXELBREW");brand.getStyleClass().add("brand");var caption=new Label("PARAMETRIC CAD");caption.getStyleClass().add("eyebrow");
        var spacer=new Region();HBox.setHgrow(spacer,Priority.ALWAYS);
        var title=new HBox(12,brand,caption,spacer,new Label("Design workspace"));title.getStyleClass().add("title-strip");
        views.setId("standard-view");views.getItems().setAll("Isometric","Top","Front","Right");views.setValue("Isometric");views.setOnAction(e->viewport.standardView(views.getValue()));
        var themes=new ComboBox<String>();themes.setId("appearance");themes.getItems().setAll("System","Light","Dark");themes.getSelectionModel().select(appearanceChoice.ordinal());
        themes.setOnAction(e->{appearanceChoice=Appearance.Choice.values()[themes.getSelectionModel().getSelectedIndex()];appearance.save(appearanceChoice);applyAppearance();});
        var toolbar=new ToolBar(button("New","new",()->requestChange(()->{cancelTool();selectedFeature=null;selectedBody=null;fitNext=true;controller.newDocument();})),
                button("Open…","open",()->requestChange(this::open)),button("Save","save",()->save(false)),button("Save as…","save-as",()->save(true)),new Separator(),undo,redo,new Separator(),rectangle,circle,extrude,cut,new Separator(),export);
        var viewbar=new HBox(9,new Label("VIEW"),views,button("Fit","fit",()->viewport.fit(meshes())),new Region(),new Label("Appearance"),themes);viewbar.getStyleClass().add("view-strip");
        HBox.setHgrow(viewbar.getChildren().get(3),Priority.ALWAYS);setTop(new VBox(title,toolbar));
        tree.setId("model-tree");tree.setShowRoot(false);tree.getSelectionModel().selectedItemProperty().addListener((o,old,item)->{
            if(refreshing||item==null||item.getValue().id()==null||tool!=Tool.NONE)return;
            var entry=item.getValue();if(entry.body())selectBody(entry.id(),-1);else selectFeature(entry.id());
        });
        var left=new VBox(10,heading("MODEL"),tree);left.getStyleClass().add("side-panel");left.setPrefWidth(220);left.setMinWidth(185);VBox.setVgrow(tree,Priority.ALWAYS);
        inspector.getStyleClass().add("side-panel");inspector.setPrefWidth(270);inspector.setMinWidth(240);error.setWrapText(true);error.setId("tool-error");error.getStyleClass().add("error");
        selectionLabel.getStyleClass().add("selection-title");var scroll=new ScrollPane(form);scroll.setFitToWidth(true);scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);VBox.setVgrow(scroll,Priority.ALWAYS);
        error.managedProperty().bind(error.textProperty().isNotEmpty());error.visibleProperty().bind(error.managedProperty());
        inspector.getChildren().addAll(heading("PROPERTIES"),selectionLabel,scroll,error,toolActions);
        var center=new BorderPane(viewport);center.setTop(viewbar);center.getStyleClass().add("viewport-panel");
        var split=new SplitPane(left,center,inspector);split.setDividerPositions(0.18,0.78);setCenter(split);
        cursor.setId("history-cursor");cursor.setMin(0);cursor.setBlockIncrement(1);cursor.setMajorTickUnit(1);cursor.setMinorTickCount(0);cursor.setSnapToTicks(true);
        cursor.valueProperty().addListener((o,old,value)->{int position=(int)Math.round(value.doubleValue());if(!refreshing&&position!=controller.snapshot().document().cursor()){cancelTool();controller.cursor(position);}});
        var timeline=new VBox(8,new HBox(12,heading("HISTORY"),cursorLabel),history,cursor);timeline.getStyleClass().add("timeline");
        var historyScroll=new ScrollPane(history);historyScroll.setFitToHeight(true);historyScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);historyScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);timeline.getChildren().set(1,historyScroll);
        progress.setPrefSize(16,16);progress.setMaxSize(16,16);status.setId("status");status.setWrapText(true);
        var statusSpacer=new Region();HBox.setHgrow(statusSpacer,Priority.ALWAYS);var statusbar=new HBox(10,progress,status,statusSpacer,new Label("mm   ·   OCCT 7.9   ·   Grid 10 mm"));statusbar.getStyleClass().add("status-strip");setBottom(new VBox(timeline,statusbar));
        viewport.onSelection((id,face)->{if(tool!=Tool.NONE||controller.rebuilding())return;if(controller.snapshot().document().features().stream().anyMatch(f->f.id().equals(id)))selectFeature(id);else selectBody(id,face);});
        controller.onChanged(this::refresh);stage.focusedProperty().addListener((o,old,focused)->{if(focused&&appearanceChoice==Appearance.Choice.SYSTEM)applyAppearance();});
        addEventFilter(KeyEvent.KEY_PRESSED,e->{
            if(e.getCode()==KeyCode.ESCAPE){cancelTool();e.consume();}
            else if(e.isShortcutDown())switch(e.getCode()) {
                case S -> {save(e.isShiftDown());e.consume();}
                case O -> {requestChange(this::open);e.consume();}
                case N -> {requestChange(()->{cancelTool();controller.newDocument();});e.consume();}
                case Z -> {if(e.isShiftDown())controller.redo();else controller.undo();cancelTool();e.consume();}
                case Y -> {controller.redo();cancelTool();e.consume();}
                default -> { }
            }
        });
        stage.setOnCloseRequest(e->{e.consume();requestChange(()->{close();stage.hide();Platform.exit();});});
        applyAppearance();refresh();
        if(!registry.diagnostics().isEmpty())status.setText(String.join(" | ",registry.diagnostics()));
    }
    private Stage stage(){return stage;}
    private ExporterRegistry registry(){return registry;}
    public DocumentController controller(){return controller;}
    public Viewport viewport(){return viewport;}
    private static Label heading(String text){var label=new Label(text);label.getStyleClass().add("eyebrow");return label;}
    private Button button(String text,String id,Runnable action){var button=new Button(text);button.setId(id);button.setOnAction(e->attempt(action));return button;}
    private void attempt(Runnable action){try{error.setText("");action.run();}catch(RuntimeException failure){error.setText(Objects.toString(failure.getMessage(),failure.toString()));}}
    private Map<String,MeshData> meshes(){var result=new LinkedHashMap<String,MeshData>();controller.build().bodies().forEach(b->result.put(b.id(),b.mesh()));return result;}
    private void refresh() {
        if(closed)return;refreshing=true;
        try {
            var snapshot=controller.snapshot();var document=snapshot.document();
            boolean changed=!lastFeatures.equals(document.features())||!lastDocument.equals(document.id());
            if(!lastDocument.equals(document.id())){selectedFeature=null;selectedBody=null;fitNext=true;}
            if(changed||lastBuild!=controller.build())rebuildTree(document);
            if(lastBuild!=controller.build()) {
                viewport.showBodies(meshes());if(fitNext&&!controller.build().bodies().isEmpty()){viewport.fit(meshes());fitNext=false;}
                lastBuild=controller.build();
                if(tool==Tool.NONE){updateFeatureDiagnostics();var feature=selected();if(feature!=null)selectionLabel.setText(shortName(feature));}
            }
            synchronizeTreeSelection();
            viewport.showSketches(document.features().subList(0,document.cursor()),selectedFeature);
            if(tool==Tool.RECTANGLE||tool==Tool.CIRCLE)sketchPreview();
            if(tool==Tool.NONE&&changed)inspect();
            lastFeatures=document.features();lastDocument=document.id();
            cursor.setMax(document.features().size());cursor.setValue(document.cursor());cursorLabel.setText(document.cursor()+" / "+document.features().size());
            history.getChildren().clear();for(int i=0;i<document.features().size();i++){var feature=document.features().get(i);var chip=button((i+1)+"  "+shortName(feature),"history-"+i,()->selectFeature(feature.id()));chip.getStyleClass().add(i<document.cursor()?"history-active":"history-future");if(feature.id().equals(selectedFeature))chip.getStyleClass().add("history-selected");history.getChildren().add(chip);}
            undo.setDisable(!snapshot.canUndo()||controller.ioBusy());redo.setDisable(!snapshot.canRedo()||controller.ioBusy());export.setDisable(!controller.canExport());
            boolean atEnd=document.cursor()==document.features().size()&&!controller.ioBusy();rectangle.setDisable(!atEnd);circle.setDisable(!atEnd);extrude.setDisable(!atEnd||!(selected() instanceof Feature.Sketch));cut.setDisable(!atEnd||!(selected() instanceof Feature.Sketch s&&s.profile() instanceof Feature.Circle)||controller.rebuilding()||controller.build().bodies().isEmpty());
            cursor.setDisable(controller.ioBusy()||tool!=Tool.NONE);progress.setVisible(controller.rebuilding()||controller.ioBusy());
            String diagnostics=controller.build().diagnostics().stream().filter(d->d.state()==Evaluation.State.FAILED||d.state()==Evaluation.State.BLOCKED).map(d->d.featureId()+": "+d.message()).reduce((a,b)->a+" | "+b).orElse("");
            status.setText(!controller.error().isBlank()?controller.error():controller.ioBusy()?"Working with file…":controller.rebuilding()?"Rebuilding…":!diagnostics.isBlank()?diagnostics:controller.hasPreview()?"Preview — release or Enter to commit; Escape to cancel":controller.build().bodies().size()+" bodies  ·  "+String.format(Locale.ROOT,"%.3f mm³",controller.build().bodies().stream().mapToDouble(DocumentController.Body::volume).sum()));
            stage.setTitle((controller.dirty()?"● ":"")+(controller.path()==null?"Untitled":controller.path().getFileName())+" — VoxelBrew CAD");
        }finally{refreshing=false;}
    }
    private static String shortName(Feature feature){return feature instanceof Feature.Sketch s?(s.profile() instanceof Feature.Circle?"Circle":"Rectangle"):feature instanceof Feature.Extrude?"Extrude":"Through cut";}
    private void rebuildTree(CadDocument document) {
        var root=new TreeItem<>(new Entry(null,"Document",false));var features=new TreeItem<>(new Entry(null,"Features",false));features.setExpanded(true);
        for(int i=0;i<document.features().size();i++) {var feature=document.features().get(i);String diagnostic=controller.build().diagnostics().stream().filter(d->d.featureId().equals(feature.id())&&d.state()!=Evaluation.State.OK).map(d->"  ["+d.state()+"]").findFirst().orElse("");var item=new TreeItem<>(new Entry(feature.id(),(i+1)+"  "+shortName(feature)+diagnostic,false));features.getChildren().add(item);}
        var bodies=new TreeItem<>(new Entry(null,"Bodies",true));bodies.setExpanded(true);int index=1;for(var body:controller.build().bodies())bodies.getChildren().add(new TreeItem<>(new Entry(body.id(),"Body "+index++,true)));root.getChildren().add(features);root.getChildren().add(bodies);tree.setRoot(root);
        for(var item:features.getChildren())if(item.getValue().id().equals(selectedFeature))tree.getSelectionModel().select(item);
    }
    private void synchronizeTreeSelection() {
        if(tree.getRoot()==null)return;
        String id=selectedBody==null?selectedFeature:selectedBody;
        for(var group:tree.getRoot().getChildren())for(var item:group.getChildren())if(item.getValue().id().equals(id)){tree.getSelectionModel().select(item);return;}
        tree.getSelectionModel().clearSelection();
    }
    private Feature selected(){return controller.snapshot().document().features().stream().filter(f->f.id().equals(selectedFeature)).findFirst().orElse(null);}
    private void selectFeature(String id){if(tool!=Tool.NONE)return;selectedFeature=id;selectedBody=null;viewport.select(null);viewport.showSketches(controller.snapshot().document().features().subList(0,controller.snapshot().document().cursor()),id);inspect();refresh();}
    private void selectBody(String id,int face){selectedBody=id;controller.build().bodies().stream().filter(b->b.id().equals(id)).findFirst().ifPresent(b->selectedFeature=b.output().featureId());viewport.select(id);inspect();if(face>=0)selectionLabel.setText("Face "+face+" · "+shortName(Objects.requireNonNull(selected())));refresh();}
    private void inspect(){
        if(tool!=Tool.NONE)return;form.getChildren().clear();toolActions.getChildren().clear();fields.clear();error.setText("");var feature=selected();
        if(feature==null){selectionLabel.setText("Start a design");var hint=new Label("Draw a rectangle or circle on XY, then extrude its closed profile.\n\nMiddle-drag to orbit\nShift + middle-drag to pan\nScroll to zoom");hint.setWrapText(true);form.getChildren().add(hint);return;}
        selectionLabel.setText(shortName(feature));var identity=new Label(feature.id());identity.setWrapText(true);identity.getStyleClass().add("muted");form.getChildren().add(identity);
        if(feature instanceof Feature.Sketch s){
            if(s.profile() instanceof Feature.Rectangle r){dimension(feature,"x",r.x());dimension(feature,"y",r.y());dimension(feature,"width",r.width());dimension(feature,"height",r.height());}
            else{var c=(Feature.Circle)s.profile();dimension(feature,"centerX",c.centerX());dimension(feature,"centerY",c.centerY());dimension(feature,"diameter",c.diameter());}
            dimension(feature,"offsetZ",s.plane().offsetZ());form.getChildren().add(new Label("Plane: "+s.plane().id()));
        }else if(feature instanceof Feature.Extrude e){dimension(feature,"distance",e.distance());referenceEditor(feature,e.profile(),null);}
        else if(feature instanceof Feature.ThroughCut c)referenceEditor(feature,c.profile(),c.body());
        form.getChildren().add(featureDiagnostics);updateFeatureDiagnostics();
    }
    private void updateFeatureDiagnostics(){
        featureDiagnostics.getChildren().clear();
        controller.build().diagnostics().stream().filter(d->d.featureId().equals(selectedFeature)&&d.state()!=Evaluation.State.OK).forEach(d->{var label=new Label(d.state()+": "+d.message());label.setWrapText(true);label.getStyleClass().add("error");featureDiagnostics.getChildren().add(label);});
    }
    private void dimension(Feature feature,String name,double value){var field=field("dimension-"+name,name+" (mm)",value);var apply=button("Apply","apply-"+name,()->controller.edit(feature.id(),name,number(field,name)));field.setOnAction(e->apply.fire());form.getChildren().add(apply);}
    private void referenceEditor(Feature feature,Feature.ProfileRef profile,Feature.BodyRef body){
        var earlier=controller.snapshot().document().features().stream().takeWhile(f->!f.id().equals(feature.id())).toList();
        var sketches=new ComboBox<String>();sketches.setId("repair-sketch");earlier.stream().filter(f->f instanceof Feature.Sketch).forEach(f->sketches.getItems().add(f.id()));sketches.setValue(profile.sketchId());
        form.getChildren().addAll(new Label("Input sketch"),sketches);var bodies=new ComboBox<String>();bodies.setId("repair-body");
        if(body!=null){earlier.stream().filter(f->!(f instanceof Feature.Sketch)).forEach(f->bodies.getItems().add(f.id()));bodies.setValue(body.featureId());form.getChildren().addAll(new Label("Input body feature"),bodies);}
        form.getChildren().add(button("Repair inputs","repair",()->{
            var s=(Feature.Sketch)earlier.stream().filter(f->f instanceof Feature.Sketch&&f.id().equals(sketches.getValue())).findFirst().orElseThrow(()->new IllegalArgumentException("Choose an earlier sketch"));
            var ref=new Feature.ProfileRef(s.id(),s.profileId());controller.replaceFeature(feature instanceof Feature.Extrude e?new Feature.Extrude(e.id(),ref,e.distance()):new Feature.ThroughCut(feature.id(),ref,new Feature.BodyRef(Objects.requireNonNull(bodies.getValue(),"Choose an earlier body"),"body")));
        }));
    }
    private TextField field(String id,String label,double initial){var text=new TextField(Double.toString(initial));text.setId(id);fields.put(id,text);form.getChildren().addAll(new Label(label),text);return text;}
    private static double number(TextField field,String name){try{double value=Double.parseDouble(field.getText().trim());if(!Double.isFinite(value))throw new NumberFormatException();return value;}catch(NumberFormatException failure){throw new IllegalArgumentException(name+" must be a finite number");}}
    private double value(String key){return number(fields.get(key),key);}
    private void beginSketch(boolean isCircle){
        cancelTool();tool=isCircle?Tool.CIRCLE:Tool.RECTANGLE;firstPoint=null;form.getChildren().clear();fields.clear();selectionLabel.setText(isCircle?"New circle":"New rectangle");
        field("sketch-x",isCircle?"Center X (mm)":"X (mm)",isCircle?10:0);field("sketch-y",isCircle?"Center Y (mm)":"Y (mm)",isCircle?10:0);
        if(isCircle)field("sketch-diameter","Diameter (mm)",4);else{field("sketch-width","Width (mm)",60);field("sketch-height","Height (mm)",40);}
        var offset=field("sketch-offset","Plane offset Z (mm)",0);offset.textProperty().addListener((o,a,b)->attempt(()->viewport.drawOnPlane(value("sketch-offset"),this::sketchPoint)));
        fields.values().forEach(field->field.textProperty().addListener((o,a,b)->sketchPreview()));
        finish=button("Finish sketch","finish-sketch",()->{
            double z=value("sketch-offset");String id=(isCircle?"circle-":"rectangle-")+UUID.randomUUID();
            var plane=new Feature.Plane(z==0?"origin-xy":"plane-"+UUID.randomUUID(),z);
            Feature.Profile profile=isCircle?new Feature.Circle(value("sketch-x"),value("sketch-y"),value("sketch-diameter")):new Feature.Rectangle(value("sketch-x"),value("sketch-y"),value("sketch-width"),value("sketch-height"));
            var feature=new Feature.Sketch(id,id+".profile",plane,profile);controller.add(feature);tool=Tool.NONE;viewport.stopDrawing();selectedFeature=id;inspect();refresh();
        });finish.getStyleClass().add("primary");toolActions.getChildren().setAll(finish,button("Cancel","cancel-tool",this::cancelTool));var hint=new Label("Click two points, or enter exact dimensions.");hint.setWrapText(true);form.getChildren().add(hint);
        views.setValue("Top");viewport.standardView("Top");viewport.fit(meshes());viewport.drawOnPlane(0,this::sketchPoint);refresh();
    }
    private void sketchPreview(){
        try {
            Feature.Profile profile=tool==Tool.CIRCLE?new Feature.Circle(value("sketch-x"),value("sketch-y"),value("sketch-diameter")):new Feature.Rectangle(value("sketch-x"),value("sketch-y"),value("sketch-width"),value("sketch-height"));
            if(profile instanceof Feature.Rectangle r&&(r.width()<=0||r.height()<=0)||profile instanceof Feature.Circle c&&c.diameter()<=0)return;
            var document=controller.snapshot().document();var features=new ArrayList<>(document.features().subList(0,document.cursor()));
            features.add(new Feature.Sketch("preview-sketch","preview-profile",new Feature.Plane("preview-plane",value("sketch-offset")),profile));
            viewport.showSketches(features,"preview-sketch");
        }catch(RuntimeException ignored){/* Incomplete input stays editable; Finish performs engine validation. */}
    }
    private void sketchPoint(Point3D point){
        if(firstPoint==null){firstPoint=point;fields.get("sketch-x").setText(format(point.getX()));fields.get("sketch-y").setText(format(point.getY()));status.setText(tool==Tool.CIRCLE?"Choose circle radius":"Choose opposite corner");}
        else{
            if(tool==Tool.CIRCLE)fields.get("sketch-diameter").setText(format(2*Math.hypot(point.getX()-firstPoint.getX(),point.getY()-firstPoint.getY())));
            else{fields.get("sketch-x").setText(format(Math.min(point.getX(),firstPoint.getX())));fields.get("sketch-y").setText(format(Math.min(point.getY(),firstPoint.getY())));fields.get("sketch-width").setText(format(Math.abs(point.getX()-firstPoint.getX())));fields.get("sketch-height").setText(format(Math.abs(point.getY()-firstPoint.getY())));}
            firstPoint=null;status.setText("Dimensions captured — Finish sketch to commit");
        }
    }
    private static String format(double value){return String.format(Locale.ROOT,"%.3f",value);}
    private void beginExtrusion(){
        var feature=selected();if(!(feature instanceof Feature.Sketch sketch))throw new IllegalArgumentException("Select a closed sketch profile first");
        cancelTool();extrusionSketch=sketch;tool=Tool.EXTRUDE;selectionLabel.setText("Extrude profile");form.getChildren().clear();fields.clear();
        var distance=field("extrude-distance","Signed distance (mm)",5);
        finish=button("Commit","commit-extrusion",this::commitExtrusion);finish.getStyleClass().add("primary");toolActions.getChildren().setAll(finish,button("Cancel","cancel-tool",this::cancelTool));var hint=new Label("Drag the gold handle, or type a distance.\nEnter commits; Escape cancels.");hint.setWrapText(true);form.getChildren().add(hint);
        distance.textProperty().addListener((o,a,b)->{
            try{double value=number(distance,"Distance");controller.previewExtrusion(sketch,value);viewport.updateExtrusionDistance(value);finish.setDisable(false);error.setText("");}
            catch(RuntimeException failure){finish.setDisable(true);error.setText(failure.getMessage());}
        });distance.setOnAction(e->{if(!finish.isDisabled())finish.fire();});
        views.setValue("Isometric");viewport.standardView("Isometric");controller.previewExtrusion(sketch,5);
        viewport.extrusionHandle(sketch,5,v->distance.setText(format(v)),()->{if(!finish.isDisabled())commitExtrusion();});distance.requestFocus();distance.selectAll();refresh();
    }
    private void commitExtrusion(){controller.commitPreview();tool=Tool.NONE;viewport.clearHandle();var features=controller.snapshot().document().features();selectedFeature=features.getLast().id();inspect();refresh();}
    private void beginCut(){
        if(!(selected() instanceof Feature.Sketch sketch)||!(sketch.profile() instanceof Feature.Circle))throw new IllegalArgumentException("Select a circle sketch");
        var choices=controller.build().bodies();if(choices.isEmpty())throw new IllegalArgumentException("A target body is required");
        var dialog=new ChoiceDialog<>(choices.getFirst().id(),choices.stream().map(DocumentController.Body::id).toList());dialog.setTitle("Through-all cut");dialog.setHeaderText("Choose the target body");dialog.getDialogPane().lookupButton(ButtonType.OK).setId("cut-confirm");
        Dialogs.show(dialog,stage).ifPresent(id->{var target=choices.stream().filter(b->b.id().equals(id)).findFirst().orElseThrow();controller.add(new Feature.ThroughCut("cut-"+UUID.randomUUID(),new Feature.ProfileRef(sketch.id(),sketch.profileId()),target.output()));tool=Tool.NONE;selectedFeature=controller.snapshot().document().features().getLast().id();inspect();refresh();});
    }
    private void cancelTool(){tool=Tool.NONE;viewport.stopDrawing();viewport.clearHandle();controller.cancelPreview();firstPoint=null;extrusionSketch=null;inspect();refresh();}
    private void open(){FileDialog.choose(stage,false,controller.path()).ifPresent(path->{cancelTool();controller.open(path).exceptionally(failure->null);});}
    private CompletableFuture<Void> save(boolean saveAs){
        if(controller.ioBusy())return CompletableFuture.failedFuture(new IllegalStateException("File operation in progress"));
        Optional<Path> chosen=!saveAs&&controller.path()!=null?Optional.of(controller.path()):FileDialog.choose(stage,true,controller.path());
        if(chosen.isEmpty())return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException());
        Path path=chosen.get();boolean existing=Files.exists(path);
        if(existing&&(saveAs||controller.path()==null||!path.equals(controller.path()))&&!confirmOverwrite(path))return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException());
        return controller.save(path,existing);
    }
    private boolean confirmOverwrite(Path path){var alert=new Alert(Alert.AlertType.CONFIRMATION,"Replace "+path+"?",ButtonType.YES,ButtonType.NO);return Dialogs.show(alert,stage).orElse(ButtonType.NO)==ButtonType.YES;}
    private void requestChange(Runnable action){
        if(controller.ioBusy()){error.setText("Wait for the current file operation");return;}
        if(!controller.dirty()){action.run();return;}
        var save=new ButtonType("Save",ButtonBar.ButtonData.YES);var discard=new ButtonType("Discard",ButtonBar.ButtonData.NO);
        var alert=new Alert(Alert.AlertType.CONFIRMATION,"Save changes to this design?",save,discard,ButtonType.CANCEL);alert.setHeaderText("Unsaved changes");
        alert.getDialogPane().lookupButton(save).setId("unsaved-save");alert.getDialogPane().lookupButton(discard).setId("unsaved-discard");alert.getDialogPane().lookupButton(ButtonType.CANCEL).setId("unsaved-cancel");
        var choice=Dialogs.show(alert,stage).orElse(ButtonType.CANCEL);if(choice==discard)action.run();else if(choice==save)save(false).thenRun(()->requestChange(action)).exceptionally(failure->null);
    }
    private void applyAppearance(){
        long request=++appearanceRequest;var choice=appearanceChoice;
        if(choice!=Appearance.Choice.SYSTEM){setDark(choice==Appearance.Choice.DARK);return;}
        CompletableFuture.supplyAsync(Appearance::systemDark,preferencesWorker).thenAccept(dark->Platform.runLater(()->{if(!closed&&request==appearanceRequest)setDark(dark);}));
    }
    private void setDark(boolean dark){getStyleClass().removeAll("light","dark");getStyleClass().add(dark?"dark":"light");viewport.setDark(dark);}
    @Override public void close(){if(closed)return;closed=true;controller.close();preferencesWorker.shutdown();try{registry.close();}catch(IOException ignored){/* Class loader shutdown has no document writes. */}}
}
