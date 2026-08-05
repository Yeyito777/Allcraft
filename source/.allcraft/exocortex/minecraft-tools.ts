interface ToolsetContext {
  conversationId: string;
  modulePath: string;
  moduleDirectory: string;
  workingDirectory: string;
}

const IMPLEMENTATION_SHA256 = "d93221281db4ebb585e69138734aa914918a63d73b3e860bf0da0ebd5b6ba59a";

function implementationPath(moduleDirectory: string): string {
  return `${moduleDirectory.replace(/[\\/]+$/, "")}/minecraft-tools-impl.ts`;
}

function fileUrl(path: string): string {
  const normalized = path.replace(/\\/g, "/");
  return new URL(`file://${normalized.startsWith("/") ? "" : "/"}${normalized}`).href;
}

export default {
  apiVersion: 1,
  id: "allcraft.minecraft",

  async create(context: ToolsetContext) {
    const path = implementationPath(context.moduleDirectory);
    const bytes = await Bun.file(path).arrayBuffer();
    const actual = new Bun.CryptoHasher("sha256").update(bytes).digest("hex");
    if (actual !== IMPLEMENTATION_SHA256) {
      throw new Error(`Allcraft Minecraft tool implementation digest mismatch: ${path}`);
    }
    const implementation = await import(`${fileUrl(path)}?sha256=${IMPLEMENTATION_SHA256}`);
    const toolset = implementation.default;
    if (!toolset || typeof toolset.create !== "function") {
      throw new Error(`Allcraft Minecraft tool implementation has no factory: ${path}`);
    }
    return await toolset.create(context);
  },
};
