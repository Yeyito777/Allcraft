import { afterEach, describe, expect, test } from "bun:test";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import toolset from "../../source/.allcraft/exocortex/minecraft-tools-impl";
import toolsetEntry from "../../source/.allcraft/exocortex/minecraft-tools";

const roots: string[] = [];

afterEach(async () => {
  await Promise.all(roots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

async function fixture(name: string) {
  const root = await mkdtemp(resolve(tmpdir(), `allcraft-${name}-`));
  roots.push(root);
  const sourceRoot = resolve(root, "source");
  const moduleDirectory = resolve(sourceRoot, ".allcraft/exocortex");
  await mkdir(moduleDirectory, { recursive: true });

  const files: Record<string, string> = {
    "client/assets/example/blockstates/ruby_block.json": JSON.stringify({ variants: { "": { model: "example:block/ruby_block" } } }),
    "client/assets/example/models/block/ruby_block.json": JSON.stringify({ parent: "minecraft:block/cube_all", textures: { all: "example:block/ruby_block" } }),
    "client/assets/example/textures/block/ruby_block.png": "not-a-real-png",
    "client/assets/example/particles/ruby_spark.json": JSON.stringify({ textures: ["example:particle/ruby_spark"] }),
    "client/assets/example/sounds.json": JSON.stringify({ "ruby.chime": { sounds: [{ name: "example:block/ruby_chime" }] } }),
    "server/data/example/recipe/ruby_block.json": JSON.stringify({ type: "minecraft:crafting_shaped", result: { id: "example:ruby_block" } }),
    "client/net/minecraft/example/RubyFeature.java": "package net.minecraft.example;\npublic class RubyFeature { String id = \"example:ruby_block\"; }\n",
    "client/net/minecraft/world/entity/EntityTypeIds.java": "package net.minecraft.world.entity;\npublic class EntityTypeIds { Object RUBY_COW = create(\"ruby_cow\"); }\n",
  };
  for (const [path, content] of Object.entries(files)) {
    const destination = resolve(sourceRoot, path);
    await mkdir(resolve(destination, ".."), { recursive: true });
    await writeFile(destination, content);
  }

  const instance = toolset.create({
    conversationId: name,
    modulePath: resolve(moduleDirectory, "minecraft-tools.ts"),
    moduleDirectory,
    workingDirectory: resolve(root, "conversation"),
  });
  const find = (toolName: string) => {
    const tool = instance.tools.find((candidate: any) => candidate.name === toolName);
    if (!tool) throw new Error(`Missing tool ${toolName}`);
    return tool;
  };
  const call = async (toolName: string, input: Record<string, unknown>, signal?: AbortSignal) => {
    const result = await find(toolName).execute(input, {}, signal);
    if (result.isError) throw new Error(result.output);
    return JSON.parse(result.output);
  };
  return { root, sourceRoot, moduleDirectory, instance, find, call };
}

describe("Allcraft Minecraft custom tools", () => {
  test("the compact pinned entry loads the complete implementation", async () => {
    const tools = await fixture("entry");
    const loaded = await toolsetEntry.create({
      conversationId: "entry",
      modulePath: resolve(tools.moduleDirectory, "minecraft-tools.ts"),
      moduleDirectory: resolve(import.meta.dir, "../../source/.allcraft/exocortex"),
      workingDirectory: tools.root,
    });
    expect(loaded.tools.map((tool: any) => tool.name)).toEqual(["minecraft_glob", "minecraft_grep"]);
  });

  test("exports two conversation-scoped read-only tools", async () => {
    const first = await fixture("first");
    const second = await fixture("second");
    expect(first.instance.tools.map((tool: any) => tool.name)).toEqual(["minecraft_glob", "minecraft_grep"]);
    expect(first.instance.tools.every((tool: any) => tool.parallelSafety === "safe" && tool.resourceClass === "filesystem_scan")).toBe(true);
    expect(first.instance).not.toBe(second.instance);
  });

  test("discovers categories and semantic resource IDs", async () => {
    const tools = await fixture("inventory");
    const categories = await tools.call("minecraft_glob", { kind: "categories" });
    expect(categories.source_root).toBe(tools.sourceRoot);
    expect(categories.categories.find((entry: any) => entry.name === "blocks")?.count).toBe(1);
    expect(categories.categories.find((entry: any) => entry.name === "recipes")?.count).toBe(1);
    expect(categories.categories.find((entry: any) => entry.name === "sounds")?.count).toBe(1);
    expect(categories.categories.find((entry: any) => entry.name === "entities")?.count).toBe(1);

    const blocks = await tools.call("minecraft_glob", { kind: "blocks", namespace: "example" });
    expect(blocks.entries[0].id).toBe("example:ruby_block");
    expect(blocks.entries[0].path).toBe("client/assets/example/blockstates/ruby_block.json");
  });

  test("searches definitions, source lines, and resource relationships", async () => {
    const tools = await fixture("search");
    const definitions = await tools.call("minecraft_grep", { query: "ruby_block", relation: "definition" });
    expect(definitions.results.some((result: any) => result.definition?.id === "example:ruby_block")).toBe(true);

    const content = await tools.call("minecraft_grep", { query: "RubyFeature", relation: "content", domains: ["java"] });
    expect(content.results.some((result: any) => result.match?.path.endsWith("RubyFeature.java") && result.match.line === 2)).toBe(true);

    const references = await tools.call("minecraft_grep", { query: "example:block/ruby_block", relation: "references", domains: ["models"] });
    expect(references.results.some((result: any) => result.target?.kind === "textures" && result.target.id === "example:block/ruby_block")).toBe(true);

    const referencedBy = await tools.call("minecraft_grep", { query: "example:block/ruby_block", relation: "referenced_by" });
    expect(referencedBy.results.some((result: any) => result.source?.kind === "models")).toBe(true);
  });

  test("paginates deterministically", async () => {
    const tools = await fixture("pages");
    const first = await tools.call("minecraft_glob", { kind: "blocks", limit: 1 });
    expect(first.entries).toHaveLength(1);
    expect(first.next_cursor).toBe(null);

    const broadFirst = await tools.call("minecraft_glob", { kind: "models", limit: 1, pattern: "**" });
    expect(broadFirst.cursor).toBe("0");
    expect(broadFirst.entries).toHaveLength(1);
  });

  test("keeps indexes isolated by conversation source root", async () => {
    const first = await fixture("isolated-a");
    const second = await fixture("isolated-b");
    const extra = resolve(second.sourceRoot, "client/assets/second/blockstates/only_second.json");
    await mkdir(resolve(extra, ".."), { recursive: true });
    await writeFile(extra, "{}");

    const firstBlocks = await first.call("minecraft_glob", { kind: "blocks", pattern: "*only_second*" });
    const secondBlocks = await second.call("minecraft_glob", { kind: "blocks", pattern: "*only_second*" });
    expect(firstBlocks.total).toBe(0);
    expect(secondBlocks.total).toBe(1);
  });

  test("honors cancellation before work begins", async () => {
    const tools = await fixture("cancel");
    const controller = new AbortController();
    controller.abort();
    await expect(tools.find("minecraft_glob").execute({ kind: "categories" }, {}, controller.signal)).rejects.toMatchObject({ name: "AbortError" });
  });
});
