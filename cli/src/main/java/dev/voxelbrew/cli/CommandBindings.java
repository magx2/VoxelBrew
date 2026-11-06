package dev.voxelbrew.cli;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;
import dev.voxelbrew.engine.feature.FeatureRegistry;

/** Each command is unscoped: a new parser gets fresh mutable argument state. */
@Module
abstract class CommandBindings {
    @Binds
    @IntoMap
    @ClassKey(ImportCommands.Importers.class)
    abstract Object importers(ImportCommands.Importers command);

    @Binds
    @IntoMap
    @ClassKey(ImportCommands.ImportFile.class)
    abstract Object importFile(ImportCommands.ImportFile command);

    @Provides
    static FeatureRegistry.Factory features() {
        return FeatureRegistry::new;
    }

    @Binds
    @IntoMap
    @ClassKey(VoxelBrew.class)
    abstract Object voxelBrew(VoxelBrew command);

    @Binds
    @IntoMap
    @ClassKey(VoxelBrew.Exporters.class)
    abstract Object voxelBrewExporters(VoxelBrew.Exporters command);

    @Binds
    @IntoMap
    @ClassKey(VoxelBrew.Export.class)
    abstract Object voxelBrewExport(VoxelBrew.Export command);

    @Binds
    @IntoMap
    @ClassKey(VoxelBrew.Create.class)
    abstract Object voxelBrewCreate(VoxelBrew.Create command);

    @Binds
    @IntoMap
    @ClassKey(VoxelBrew.Rectangle.class)
    abstract Object voxelBrewRectangle(VoxelBrew.Rectangle command);

    @Binds
    @IntoMap
    @ClassKey(VoxelBrew.Circle.class)
    abstract Object voxelBrewCircle(VoxelBrew.Circle command);

    @Binds
    @IntoMap
    @ClassKey(VoxelBrew.Extrude.class)
    abstract Object voxelBrewExtrude(VoxelBrew.Extrude command);

    @Binds
    @IntoMap
    @ClassKey(VoxelBrew.Cut.class)
    abstract Object voxelBrewCut(VoxelBrew.Cut command);

    @Binds
    @IntoMap
    @ClassKey(VoxelBrew.Edit.class)
    abstract Object voxelBrewEdit(VoxelBrew.Edit command);

    @Binds
    @IntoMap
    @ClassKey(VoxelBrew.Cursor.class)
    abstract Object voxelBrewCursor(VoxelBrew.Cursor command);

    @Binds
    @IntoMap
    @ClassKey(VoxelBrew.Repair.class)
    abstract Object voxelBrewRepair(VoxelBrew.Repair command);

    @Binds
    @IntoMap
    @ClassKey(VoxelBrew.History.class)
    abstract Object voxelBrewHistory(VoxelBrew.History command);

    @Binds
    @IntoMap
    @ClassKey(VoxelBrew.Inspect.class)
    abstract Object voxelBrewInspect(VoxelBrew.Inspect command);

    @Binds
    @IntoMap
    @ClassKey(VoxelBrew.NativeDemo.class)
    abstract Object voxelBrewNativeDemo(VoxelBrew.NativeDemo command);

    @Binds
    @IntoMap
    @ClassKey(PrintingCommands.Split.class)
    abstract Object printingCommandsSplit(PrintingCommands.Split command);

    @Binds
    @IntoMap
    @ClassKey(PrintingCommands.Place.class)
    abstract Object printingCommandsPlace(PrintingCommands.Place command);

    @Binds
    @IntoMap
    @ClassKey(PrintingCommands.Duplicate.class)
    abstract Object printingCommandsDuplicate(PrintingCommands.Duplicate command);

    @Binds
    @IntoMap
    @ClassKey(PrintingCommands.LayOnFace.class)
    abstract Object printingCommandsLayOnFace(PrintingCommands.LayOnFace command);

    @Binds
    @IntoMap
    @ClassKey(PrintingCommands.Settings.class)
    abstract Object printingCommandsSettings(PrintingCommands.Settings command);

    @Binds
    @IntoMap
    @ClassKey(PrintingCommands.Support.class)
    abstract Object printingCommandsSupport(PrintingCommands.Support command);

    @Binds
    @IntoMap
    @ClassKey(PrintingCommands.RemoveInstance.class)
    abstract Object printingCommandsRemoveInstance(PrintingCommands.RemoveInstance command);

    @Binds
    @IntoMap
    @ClassKey(PrintingCommands.RemoveSupport.class)
    abstract Object printingCommandsRemoveSupport(PrintingCommands.RemoveSupport command);

    @Binds
    @IntoMap
    @ClassKey(PrintingCommands.Setup.class)
    abstract Object printingCommandsSetup(PrintingCommands.Setup command);

    @Binds
    @IntoMap
    @ClassKey(PrintingCommands.Validate.class)
    abstract Object printingCommandsValidate(PrintingCommands.Validate command);

    @Binds
    @IntoMap
    @ClassKey(PrintingCommands.Slicers.class)
    abstract Object printingCommandsSlicers(PrintingCommands.Slicers command);

    @Binds
    @IntoMap
    @ClassKey(PrintingCommands.Slice.class)
    abstract Object printingCommandsSlice(PrintingCommands.Slice command);

    @Binds
    @IntoMap
    @ClassKey(PrintingCommands.InspectJob.class)
    abstract Object printingCommandsInspectJob(PrintingCommands.InspectJob command);

    @Binds
    @IntoMap
    @ClassKey(FeatureCommands.Types.class)
    abstract Object featureCommandsTypes(FeatureCommands.Types command);

    @Binds
    @IntoMap
    @ClassKey(FeatureCommands.Custom.class)
    abstract Object featureCommandsCustom(FeatureCommands.Custom command);

    @Binds
    @IntoMap
    @ClassKey(SketchCommands.class)
    abstract Object sketchCommands(SketchCommands command);

    @Binds
    @IntoMap
    @ClassKey(SketchCommands.Draw.class)
    abstract Object sketchCommandsDraw(SketchCommands.Draw command);

    @Binds
    @IntoMap
    @ClassKey(SketchCommands.EditText.class)
    abstract Object sketchCommandsEditText(SketchCommands.EditText command);

    @Binds
    @IntoMap
    @ClassKey(SketchCommands.Create.class)
    abstract Object sketchCommandsCreate(SketchCommands.Create command);

    @Binds
    @IntoMap
    @ClassKey(SketchCommands.Convert.class)
    abstract Object sketchCommandsConvert(SketchCommands.Convert command);

    @Binds
    @IntoMap
    @ClassKey(SketchCommands.PutEntity.class)
    abstract Object sketchCommandsPutEntity(SketchCommands.PutEntity command);

    @Binds
    @IntoMap
    @ClassKey(SketchCommands.Constrain.class)
    abstract Object sketchCommandsConstrain(SketchCommands.Constrain command);

    @Binds
    @IntoMap
    @ClassKey(SketchCommands.RemoveConstraint.class)
    abstract Object sketchCommandsRemoveConstraint(SketchCommands.RemoveConstraint command);

    @Binds
    @IntoMap
    @ClassKey(SketchCommands.RemoveEntity.class)
    abstract Object sketchCommandsRemoveEntity(SketchCommands.RemoveEntity command);

    @Binds
    @IntoMap
    @ClassKey(SketchCommands.Profile.class)
    abstract Object sketchCommandsProfile(SketchCommands.Profile command);

    @Binds
    @IntoMap
    @ClassKey(SketchCommands.Solve.class)
    abstract Object sketchCommandsSolve(SketchCommands.Solve command);

    @Binds
    @IntoMap
    @ClassKey(SketchCommands.Listing.class)
    abstract Object sketchCommandsListing(SketchCommands.Listing command);

    @Binds
    @IntoMap
    @ClassKey(SketchCommands.Move.class)
    abstract Object sketchCommandsMove(SketchCommands.Move command);

    @Binds
    @IntoMap
    @ClassKey(SketchCommands.Polygon.class)
    abstract Object sketchCommandsPolygon(SketchCommands.Polygon command);

    @Binds
    @IntoMap
    @ClassKey(ParameterCommands.Parameters.class)
    abstract Object parameterCommandsParameters(ParameterCommands.Parameters command);

    @Binds
    @IntoMap
    @ClassKey(ParameterCommands.Configurations.class)
    abstract Object parameterCommandsConfigurations(ParameterCommands.Configurations command);

    @Binds
    @IntoMap
    @ClassKey(ParameterCommands.ListParameters.class)
    abstract Object parameterCommandsListParameters(ParameterCommands.ListParameters command);

    @Binds
    @IntoMap
    @ClassKey(ParameterCommands.SetParameter.class)
    abstract Object parameterCommandsSetParameter(ParameterCommands.SetParameter command);

    @Binds
    @IntoMap
    @ClassKey(ParameterCommands.RenameParameter.class)
    abstract Object parameterCommandsRenameParameter(ParameterCommands.RenameParameter command);

    @Binds
    @IntoMap
    @ClassKey(ParameterCommands.RemoveParameter.class)
    abstract Object parameterCommandsRemoveParameter(ParameterCommands.RemoveParameter command);

    @Binds
    @IntoMap
    @ClassKey(ParameterCommands.Bind.class)
    abstract Object parameterCommandsBind(ParameterCommands.Bind command);

    @Binds
    @IntoMap
    @ClassKey(ParameterCommands.ListConfigurations.class)
    abstract Object parameterCommandsListConfigurations(
            ParameterCommands.ListConfigurations command);

    @Binds
    @IntoMap
    @ClassKey(ParameterCommands.AddConfiguration.class)
    abstract Object parameterCommandsAddConfiguration(ParameterCommands.AddConfiguration command);

    @Binds
    @IntoMap
    @ClassKey(ParameterCommands.Override.class)
    abstract Object parameterCommandsOverride(ParameterCommands.Override command);

    @Binds
    @IntoMap
    @ClassKey(ParameterCommands.Activate.class)
    abstract Object parameterCommandsActivate(ParameterCommands.Activate command);

    @Binds
    @IntoMap
    @ClassKey(ParameterCommands.RenameConfiguration.class)
    abstract Object parameterCommandsRenameConfiguration(
            ParameterCommands.RenameConfiguration command);

    @Binds
    @IntoMap
    @ClassKey(ParameterCommands.RemoveConfiguration.class)
    abstract Object parameterCommandsRemoveConfiguration(
            ParameterCommands.RemoveConfiguration command);

    @Binds
    @IntoMap
    @ClassKey(UpdateCommands.Update.class)
    abstract Object updateCommandsUpdate(UpdateCommands.Update command);

    @Binds
    @IntoMap
    @ClassKey(UpdateCommands.Check.class)
    abstract Object updateCommandsCheck(UpdateCommands.Check command);
}
