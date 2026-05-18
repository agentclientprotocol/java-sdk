import { StreamableHttpFixtureClient } from "./protocol.js";
import { runScenario } from "./scenarios.js";
import { TranscriptRecorder } from "./transcript.js";

const endpoint = readArg("--endpoint");
if (!endpoint) {
  throw new Error("--endpoint is required");
}
const scenario = readArg("--scenario") ?? "happy-path";
const recorder = new TranscriptRecorder();
const client = new StreamableHttpFixtureClient(endpoint, recorder);

await runScenario(scenario, endpoint, client);
process.stdout.write(recorder.serialize());

function readArg(name: string): string | undefined {
  const index = process.argv.indexOf(name);
  return index >= 0 ? process.argv[index + 1] : undefined;
}
