package com.bridge.quartercore;
import java.nio.file.Path;
/** Local derived executable only; no mutable baseline or clock configuration in production. */
public final class PackagedEIdleMain {
 public static void main(String[] args)throws Exception{
  if(args.length!=4)throw new IllegalArgumentException();var b=PackagedEHandoffMain.load(Path.of(args[0]));
  if(!JournalSessionAuthority.fullToken(b).equals("2c8db9559c124f1d9d34ffe79c8d6413bb282382772554be55b9abbda33834a7"))throw new NativeSheetsModel.Rejected();
  PackagedEHandoffMain.run(args,FixedEBaselineBundle.compiledIdleWake());
 }
}
