interface ToolsetContext {
  conversationId: string;
  modulePath: string;
  moduleDirectory: string;
  workingDirectory: string;
}

interface Relation {
  kind: string;
  id: string;
}

interface InventoryEntry {
  kind: string;
  id: string;
  namespace: string;
  path: string;
  side: "client" | "server" | "shared";
  relations?: Relation[];
}

interface TextMatch {
  path: string;
  line: number;
  text: string;
  submatches: Array<{ start: number; end: number; match: string }>;
}

const MAX_LIMIT = 100;
const DEFAULT_LIMIT = 20;
const SKIPPED_DIRECTORIES = new Set([".git", ".worktrees", "build", "logs"]);
const RESOURCE_ID = /^(?:#)?([a-z0-9_.-]+):([a-z0-9_./-]+)$/;

function fail(message: string) {
  return { output: message, isError: true };
}

function json(value: unknown) {
  return { output: JSON.stringify(value, null, 2), isError: false };
}

function requireString(input: Record<string, unknown>, name: string): string {
  const value = input[name];
  if (typeof value !== "string" || !value.trim()) throw new Error(`${name} is required`);
  return value.trim();
}

function optionalString(input: Record<string, unknown>, name: string): string | undefined {
  const value = input[name];
  if (value === undefined) return undefined;
  if (typeof value !== "string") throw new Error(`${name} must be a string`);
  return value.trim() || undefined;
}

function stringArray(input: Record<string, unknown>, name: string): string[] | undefined {
  const value = input[name];
  if (value === undefined) return undefined;
  if (!Array.isArray(value) || value.some((entry) => typeof entry !== "string")) {
    throw new Error(`${name} must be an array of strings`);
  }
  return value.map((entry) => entry.trim()).filter(Boolean);
}

function page(input: Record<string, unknown>): { offset: number; limit: number } {
  const rawLimit = input.limit;
  const limit = rawLimit === undefined ? DEFAULT_LIMIT : Number(rawLimit);
  if (!Number.isInteger(limit) || limit < 1 || limit > MAX_LIMIT) {
    throw new Error(`limit must be an integer from 1 to ${MAX_LIMIT}`);
  }
  const rawCursor = input.cursor;
  const offset = rawCursor === undefined ? 0 : Number(rawCursor);
  if (!Number.isInteger(offset) || offset < 0) throw new Error("cursor must be a non-negative integer string");
  return { offset, limit };
}

function abortIfNeeded(signal?: AbortSignal): void {
  if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
}

function normalizePath(path: string): string {
  return path.replace(/\\/g, "/").replace(/^\.\//, "");
}

function joinPath(root: string, child: string): string {
  return `${normalizePath(root).replace(/\/+$/, "")}/${normalizePath(child).replace(/^\/+/, "")}`;
}

function parentPath(path: string, levels: number): string {
  const normalized = normalizePath(path).replace(/\/+$/, "");
  const parts = normalized.split("/");
  parts.splice(Math.max(normalized.startsWith("/") ? 1 : 0, parts.length - levels), levels);
  const result = parts.join("/");
  return normalized.startsWith("/") && !result.startsWith("/") ? `/${result}` : result;
}

function relativePath(root: string, path: string): string {
  const normalizedRoot = normalizePath(root).replace(/\/+$/, "");
  const normalizedPath = normalizePath(path);
  return normalizedPath.startsWith(`${normalizedRoot}/`)
    ? normalizedPath.slice(normalizedRoot.length + 1)
    : normalizedPath;
}

async function readText(path: string): Promise<string> {
  return await Bun.file(path).text();
}

function globRegex(pattern: string): RegExp {
  let result = "^";
  for (let index = 0; index < pattern.length; index++) {
    const character = pattern[index];
    if (character === "*") {
      if (pattern[index + 1] === "*") {
        result += ".*";
        index++;
      } else {
        result += "[^/]*";
      }
    } else if (character === "?") {
      result += "[^/]";
    } else {
      result += character.replace(/[\\^$+?.()|{}\[\]]/g, "\\$&");
    }
  }
  return new RegExp(result + "$", "i");
}

function uniqueRelations(relations: Relation[]): Relation[] {
  const seen = new Set<string>();
  return relations.filter((relation) => {
    const key = `${relation.kind}\0${relation.id}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

async function walk(root: string, signal?: AbortSignal): Promise<string[]> {
  const files: string[] = [];
  const glob = new Bun.Glob("**/*");
  for await (const candidate of glob.scan({ cwd: root, onlyFiles: true, dot: true, followSymlinks: false })) {
    abortIfNeeded(signal);
    const path = normalizePath(candidate);
    if (path.split("/").some((part) => SKIPPED_DIRECTORIES.has(part))) continue;
    files.push(joinPath(root, path));
  }
  files.sort();
  return files;
}

function sideFor(path: string): InventoryEntry["side"] {
  if (path.startsWith("client/")) return "client";
  if (path.startsWith("server/")) return "server";
  return "shared";
}

function resourceKind(section: "assets" | "data", directory: string, relativePath: string): string | undefined {
  if (section === "assets") {
    if (directory === "blockstates") return "blockstates";
    if (directory === "items") return "items";
    if (directory === "models") return "models";
    if (directory === "particles") return "particles";
    if (directory === "textures") return "textures";
    if (directory === "sounds") return "sound_files";
    if (directory === "shaders") return "shaders";
    if (directory === "font") return "fonts";
    if (directory === "lang") return "languages";
    if (directory === "atlases") return "atlases";
  } else {
    const names: Record<string, string> = {
      recipe: "recipes",
      recipes: "recipes",
      tags: "tags",
      loot_table: "loot_tables",
      loot_tables: "loot_tables",
      advancement: "advancements",
      advancements: "advancements",
      function: "functions",
      functions: "functions",
      worldgen: "worldgen",
      dimension: "dimensions",
      dimension_type: "dimension_types",
      enchantment: "enchantments",
      jukebox_song: "jukebox_songs",
      damage_type: "damage_types",
    };
    if (names[directory]) return names[directory];
  }
  if (relativePath.endsWith(".json")) return section === "assets" ? "assets" : "data";
  return undefined;
}

function stripExtension(path: string): string {
  return path.replace(/\.(?:json|png|ogg|mcmeta|fsh|vsh|glsl|txt|mcfunction)$/i, "");
}

function relationKind(key: string, value: string): string {
  const lowered = key.toLowerCase();
  if (lowered === "model" || lowered === "parent" || lowered.endsWith("model")) return "models";
  if (lowered.includes("texture") || lowered === "sprite") return "textures";
  if (lowered === "sound" || lowered === "name") return "sounds";
  if (lowered.includes("particle")) return "particles";
  if (lowered.includes("recipe")) return "recipes";
  if (value.includes("/")) return "resources";
  return "resource_ids";
}

function collectJsonRelations(value: unknown, namespace: string, key = "", output: Relation[] = []): Relation[] {
  if (typeof value === "string") {
    const withoutTag = value.startsWith("#") ? value.slice(1) : value;
    const explicit = withoutTag.match(RESOURCE_ID);
    if (explicit) {
      output.push({ kind: relationKind(key, value), id: `${explicit[1]}:${explicit[2]}` });
    } else if (/^[a-z0-9_./-]+$/.test(withoutTag) && withoutTag.includes("/")) {
      output.push({ kind: relationKind(key, value), id: `${namespace}:${withoutTag}` });
    }
  } else if (Array.isArray(value)) {
    for (const entry of value) collectJsonRelations(entry, namespace, key, output);
  } else if (value && typeof value === "object") {
    for (const [childKey, child] of Object.entries(value as Record<string, unknown>)) {
      const inheritedKey = /texture|sound|particle|model|recipe/i.test(key) ? key : childKey;
      collectJsonRelations(child, namespace, inheritedKey, output);
    }
  }
  return output;
}

class MinecraftIndex {
  private inventoryPromise?: Promise<InventoryEntry[]>;

  constructor(readonly sourceRoot: string) {}

  inventory(signal?: AbortSignal): Promise<InventoryEntry[]> {
    if (!this.inventoryPromise) {
      this.inventoryPromise = this.buildInventory(signal).catch((error) => {
        this.inventoryPromise = undefined;
        throw error;
      });
    }
    return this.inventoryPromise;
  }

  private async buildInventory(signal?: AbortSignal): Promise<InventoryEntry[]> {
    const files = await walk(this.sourceRoot, signal);
    const entries: InventoryEntry[] = [];

    for (const absolutePath of files) {
      abortIfNeeded(signal);
      const path = relativePath(this.sourceRoot, absolutePath);
      const resource = path.match(/^(client\/assets|server\/data)\/([^/]+)\/(.+)$/);
      if (resource) {
        const section = resource[1].endsWith("assets") ? "assets" : "data";
        const namespace = resource[2];
        const rest = resource[3];
        const slash = rest.indexOf("/");
        const directory = slash === -1 ? rest : rest.slice(0, slash);
        const child = slash === -1 ? rest : rest.slice(slash + 1);
        if (section === "assets" && rest === "sounds.json") {
          try {
            const parsed = JSON.parse(await readText(absolutePath)) as Record<string, unknown>;
            for (const [soundId, definition] of Object.entries(parsed)) {
              entries.push({
                kind: "sounds",
                id: `${namespace}:${soundId}`,
                namespace,
                path,
                side: "client",
                relations: uniqueRelations(collectJsonRelations(definition, namespace, "sound")),
              });
            }
          } catch {
            entries.push({ kind: "sounds", id: `${namespace}:<invalid-sounds.json>`, namespace, path, side: "client" });
          }
          continue;
        }

        const kind = resourceKind(section, directory, child);
        if (kind) {
          const idPath = stripExtension(child || directory);
          const entry: InventoryEntry = {
            kind,
            id: `${namespace}:${idPath}`,
            namespace,
            path,
            side: sideFor(path),
          };
          if (kind === "blockstates") {
            entries.push({ ...entry, kind: "blocks", relations: [{ kind: "blockstates", id: entry.id }] });
          }
          if (kind === "textures" && child.startsWith("entity/")) {
            entries.push({ ...entry, kind: "entity_textures" });
          }
          if (absolutePath.endsWith(".json")) {
            try {
              const parsed = JSON.parse(await readText(absolutePath));
              entry.relations = uniqueRelations(collectJsonRelations(parsed, namespace));
            } catch {
              // Malformed resources remain discoverable and will be rejected by the build later.
            }
          }
          entries.push(entry);
          continue;
        }
      }

      const javaCategory = this.javaCategory(path);
      if (javaCategory) {
        const text = await readText(absolutePath);
        const ids = this.javaIds(javaCategory, text);
        for (const id of ids) {
          entries.push({ kind: javaCategory, id: id.includes(":") ? id : `minecraft:${id}`, namespace: id.includes(":") ? id.split(":", 1)[0] : "minecraft", path, side: sideFor(path) });
        }
        if (ids.length === 0) {
          entries.push({ kind: javaCategory, id: `java:${path}`, namespace: "java", path, side: sideFor(path) });
        }
      }
    }

    entries.sort((left, right) => left.kind.localeCompare(right.kind) || left.id.localeCompare(right.id) || left.path.localeCompare(right.path));
    const seen = new Set<string>();
    return entries.filter((entry) => {
      const key = `${entry.kind}\0${entry.id}\0${entry.path}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  }

  private javaCategory(path: string): string | undefined {
    if (/\/(?:EntityTypes|EntityTypeIds)\.java$/.test(path)) return "entities";
    if (/\/BlockEntityTypes\.java$/.test(path) || /\/BlockEntityTypeIds\.java$/.test(path)) return "block_entities";
    if (/\/ParticleTypes\.java$/.test(path)) return "particles";
    if (/\/Blocks\.java$/.test(path)) return "blocks";
    if (/\/Items\.java$/.test(path) || /\/ItemIds\.java$/.test(path)) return "items";
    if (/\/(?:BuiltInRegistries|Registries)\.java$/.test(path)) return "registries";
    if (/\/SoundEvents\.java$/.test(path)) return "sounds";
    return undefined;
  }

  private javaIds(kind: string, text: string): string[] {
    const ids = new Set<string>();
    const patterns = [
      /\bregister\(\s*"([a-z0-9_./:-]+)"/g,
      /\bcreate\(\s*"([a-z0-9_./:-]+)"/g,
      /\bResourceKey\.create\([^;]*?Identifier\.(?:withDefaultNamespace|parse)\(\s*"([a-z0-9_./:-]+)"/gs,
    ];
    for (const pattern of patterns) {
      for (const match of text.matchAll(pattern)) ids.add(match[1]);
    }
    if (kind === "registries") {
      for (const match of text.matchAll(/public static final ResourceKey<Registry<[^>]+>>\s+([A-Z0-9_]+)/g)) {
        ids.add(match[1].toLowerCase());
      }
    }
    return [...ids];
  }

  async glob(input: Record<string, unknown>, signal?: AbortSignal): Promise<unknown> {
    const kind = requireString(input, "kind").toLowerCase();
    const entries = await this.inventory(signal);
    if (kind === "categories") {
      const counts = new Map<string, number>();
      for (const entry of entries) counts.set(entry.kind, (counts.get(entry.kind) ?? 0) + 1);
      return {
        source_root: this.sourceRoot,
        categories: [...counts].sort(([left], [right]) => left.localeCompare(right)).map(([name, count]) => ({ name, count })),
      };
    }

    const pattern = globRegex(optionalString(input, "pattern") ?? "**");
    const namespace = optionalString(input, "namespace")?.toLowerCase();
    const side = optionalString(input, "side")?.toLowerCase();
    if (side && !["client", "server", "shared", "any"].includes(side)) throw new Error("side must be client, server, shared, or any");
    const { offset, limit } = page(input);
    const matches = entries.filter((entry) =>
      entry.kind === kind
      && (!namespace || entry.namespace.toLowerCase() === namespace)
      && (!side || side === "any" || entry.side === side)
      && (pattern.test(entry.id) || pattern.test(entry.path))
    );
    const selected = matches.slice(offset, offset + limit);
    return {
      source_root: this.sourceRoot,
      kind,
      total: matches.length,
      cursor: String(offset),
      next_cursor: offset + selected.length < matches.length ? String(offset + selected.length) : null,
      entries: selected,
    };
  }

  async grep(input: Record<string, unknown>, signal?: AbortSignal): Promise<unknown> {
    const query = requireString(input, "query");
    const relation = (optionalString(input, "relation") ?? "content").toLowerCase();
    if (!["content", "definition", "references", "referenced_by"].includes(relation)) {
      throw new Error("relation must be content, definition, references, or referenced_by");
    }
    const domains = stringArray(input, "domains")?.map((domain) => domain.toLowerCase());
    const side = optionalString(input, "side")?.toLowerCase();
    if (side && !["client", "server", "shared", "any"].includes(side)) throw new Error("side must be client, server, shared, or any");
    const caseSensitive = input.case_sensitive === true;
    const { offset, limit } = page(input);
    const inventory = await this.inventory(signal);
    const compareQuery = caseSensitive ? query : query.toLowerCase();
    const contains = (value: string) => (caseSensitive ? value : value.toLowerCase()).includes(compareQuery);
    const domainMatch = (entry: InventoryEntry) => !domains?.length || domains.includes(entry.kind) || domains.includes(entry.side) || domains.includes("resources");
    const sideMatch = (entry: InventoryEntry) => !side || side === "any" || entry.side === side;

    const semantic: Array<Record<string, unknown>> = [];
    if (relation === "references") {
      for (const entry of inventory) {
        if (!domainMatch(entry) || !sideMatch(entry) || (!contains(entry.id) && !contains(entry.path))) continue;
        for (const target of entry.relations ?? []) semantic.push({ source: entry, target });
      }
    } else if (relation === "referenced_by") {
      for (const entry of inventory) {
        if (!domainMatch(entry) || !sideMatch(entry)) continue;
        for (const target of entry.relations ?? []) {
          if (contains(target.id)) semantic.push({ source: entry, target });
        }
      }
    } else {
      for (const entry of inventory) {
        if (domainMatch(entry) && sideMatch(entry) && (contains(entry.id) || contains(entry.path))) semantic.push({ definition: entry });
      }
    }

    let textMatches: TextMatch[] = [];
    if (relation === "content") {
      textMatches = await this.ripgrep(query, caseSensitive, domains, side, signal, offset + limit + 50);
    }
    const combined = [...semantic, ...textMatches.map((match) => ({ match }))];
    const selected = combined.slice(offset, offset + limit);
    return {
      source_root: this.sourceRoot,
      query,
      relation,
      total: combined.length,
      cursor: String(offset),
      next_cursor: offset + selected.length < combined.length ? String(offset + selected.length) : null,
      results: selected,
    };
  }

  private async ripgrep(
    query: string,
    caseSensitive: boolean,
    domains: string[] | undefined,
    side: string | undefined,
    signal: AbortSignal | undefined,
    wanted: number,
  ): Promise<TextMatch[]> {
    abortIfNeeded(signal);
    const args = ["rg", "--json", "--line-number", "--fixed-strings", "--max-filesize", "2M", "--max-count", String(Math.max(10, wanted))];
    if (!caseSensitive) args.push("--ignore-case");
    args.push("--glob", "!.git/**", "--glob", "!.worktrees/**", "--glob", "!build/**");
    if (side && side !== "any") args.push("--glob", `${side}/**`);
    if (domains?.length) {
      const wantsJava = domains.some((domain) => ["source", "java", "client", "server", "shared", "blocks", "items", "entities", "block_entities", "particles", "registries", "sounds"].includes(domain));
      const wantsAssets = domains.some((domain) => ["assets", "resources", "textures", "models", "blockstates", "particles", "sounds", "shaders", "fonts", "languages", "atlases"].includes(domain));
      const wantsData = domains.some((domain) => ["data", "resources", "recipes", "tags", "loot_tables", "advancements", "functions", "worldgen"].includes(domain));
      if (wantsJava || wantsAssets || wantsData) {
        if (wantsJava) args.push("--glob", "*.java");
        if (wantsAssets) args.push("--glob", "client/assets/**");
        if (wantsData) args.push("--glob", "server/data/**");
      }
    }
    args.push("--", query, ".");

    const process = Bun.spawn(args, { cwd: this.sourceRoot, stdout: "pipe", stderr: "pipe" });
    const abort = () => process.kill();
    signal?.addEventListener("abort", abort, { once: true });
    try {
      const [stdout, stderr, exitCode] = await Promise.all([
        new Response(process.stdout).text(),
        new Response(process.stderr).text(),
        process.exited,
      ]);
      abortIfNeeded(signal);
      if (exitCode !== 0 && exitCode !== 1) throw new Error(`rg failed (${exitCode}): ${stderr.trim()}`);
      const matches: TextMatch[] = [];
      for (const line of stdout.split("\n")) {
        if (!line) continue;
        const event = JSON.parse(line);
        if (event.type !== "match") continue;
        matches.push({
          path: normalizePath(event.data.path.text),
          line: event.data.line_number,
          text: String(event.data.lines.text).replace(/[\r\n]+$/, ""),
          submatches: event.data.submatches.map((match: any) => ({ start: match.start, end: match.end, match: match.match.text })),
        });
        if (matches.length >= wanted) break;
      }
      return matches;
    } finally {
      signal?.removeEventListener("abort", abort);
    }
  }
}

const GLOB_SCHEMA = {
  type: "object",
  properties: {
    kind: { type: "string", description: "Semantic category to list, or categories to discover available categories." },
    pattern: { type: "string", description: "Optional * or ** wildcard matched against resource IDs and source paths." },
    namespace: { type: "string", description: "Optional resource namespace filter." },
    side: { type: "string", enum: ["client", "server", "shared", "any"] },
    limit: { type: "integer", minimum: 1, maximum: MAX_LIMIT },
    cursor: { type: "string", description: "Pagination cursor returned by a previous call." },
  },
  required: ["kind"],
  additionalProperties: false,
};

const GREP_SCHEMA = {
  type: "object",
  properties: {
    query: { type: "string", description: "Literal text, resource ID, or path fragment to search for." },
    domains: { type: "array", items: { type: "string" }, description: "Optional semantic categories or source/assets/data domains." },
    relation: { type: "string", enum: ["content", "definition", "references", "referenced_by"] },
    side: { type: "string", enum: ["client", "server", "shared", "any"] },
    case_sensitive: { type: "boolean" },
    limit: { type: "integer", minimum: 1, maximum: MAX_LIMIT },
    cursor: { type: "string", description: "Pagination cursor returned by a previous call." },
  },
  required: ["query"],
  additionalProperties: false,
};

export default {
  apiVersion: 1,
  id: "allcraft.minecraft",

  create(context: ToolsetContext) {
    const sourceRoot = parentPath(context.moduleDirectory, 2);
    const index = new MinecraftIndex(sourceRoot);
    return {
      tools: [
        {
          name: "minecraft_glob",
          description: "Discover Minecraft content semantically. Lists blocks, items, entities, particles, sounds, textures, models, recipes, registries, and other source/resources without requiring knowledge of the mapped source layout.",
          inputSchema: GLOB_SCHEMA,
          systemHint: `This conversation targets the authoritative Allcraft world source at ${sourceRoot}. Use minecraft_glob before editing when you need to discover game content or its resource/source location. Start with kind=categories when unsure which category applies.`,
          parallelSafety: "safe",
          resourceClass: "filesystem_scan",
          defaultTimeoutMs: 120_000,
          display: { label: "Minecraft Glob", color: "#55aa55" },
          summarize(input: Record<string, unknown>) {
            return { label: "Minecraft Glob", detail: `${String(input.kind ?? "")} ${String(input.pattern ?? "")}`.trim() };
          },
          async execute(input: Record<string, unknown>, _toolContext: unknown, signal?: AbortSignal) {
            try {
              return json(await index.glob(input, signal));
            } catch (error) {
              if (error instanceof DOMException && error.name === "AbortError") throw error;
              return fail(error instanceof Error ? error.message : String(error));
            }
          },
        },
        {
          name: "minecraft_grep",
          description: "Search Minecraft source and resources semantically. Finds exact files and lines and follows references between blocks, models, textures, particles, sounds, entities, recipes, and other resources.",
          inputSchema: GREP_SCHEMA,
          systemHint: `The authoritative Allcraft world source for this conversation is ${sourceRoot}. Use minecraft_grep instead of guessing mapped class or asset paths. Use relation=references or referenced_by to follow resource relationships.`,
          parallelSafety: "safe",
          resourceClass: "filesystem_scan",
          defaultTimeoutMs: 120_000,
          display: { label: "Minecraft Grep", color: "#55ffff" },
          summarize(input: Record<string, unknown>) {
            return { label: "Minecraft Grep", detail: String(input.query ?? "") };
          },
          async execute(input: Record<string, unknown>, _toolContext: unknown, signal?: AbortSignal) {
            try {
              return json(await index.grep(input, signal));
            } catch (error) {
              if (error instanceof DOMException && error.name === "AbortError") throw error;
              return fail(error instanceof Error ? error.message : String(error));
            }
          },
        },
      ],
    };
  },
};
