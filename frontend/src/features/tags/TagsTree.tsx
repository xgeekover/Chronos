import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type DragEvent, useEffect, useState } from "react";
import { api, isAdmin, type Tag, type TagValue } from "../../api/client";
import {
  Badge,
  Button,
  ErrorNote,
  inputClass,
  Panel,
  qualityTone,
} from "../../ui/kit";

// Obsidian-style: a tag name may contain "/" which defines folders. The tree is derived from paths.
interface Leaf {
  id: string;
  label: string;
  name: string;
  key: string;
  dataType: string;
}
interface TreeNode {
  folders: Record<string, TreeNode>;
  leaves: Leaf[];
}

function buildTree(tags: Tag[]): TreeNode {
  const root: TreeNode = { folders: {}, leaves: [] };
  for (const tag of tags) {
    const parts = tag.name.split("/").filter(Boolean);
    let node = root;
    for (let i = 0; i < parts.length - 1; i++) {
      node.folders[parts[i]] ??= { folders: {}, leaves: [] };
      node = node.folders[parts[i]];
    }
    node.leaves.push({
      id: tag.id,
      label: parts[parts.length - 1] || tag.name,
      name: tag.name,
      key: tag.canonicalKey,
      dataType: tag.dataType,
    });
  }
  return root;
}

const DRAG_TYPE = "application/chronos-tag";

function TreeView({
  node,
  path,
  values,
  depth,
  collapsed,
  toggle,
  onMove,
  canMove,
}: {
  node: TreeNode;
  path: string;
  values: Map<string, TagValue>;
  depth: number;
  collapsed: Set<string>;
  toggle: (p: string) => void;
  onMove: (id: string, label: string, targetPath: string) => void;
  canMove: boolean;
}) {
  const [dropActive, setDropActive] = useState(false);
  const pad = { paddingLeft: `${depth * 18}px` };

  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setDropActive(false);
    const raw = e.dataTransfer.getData(DRAG_TYPE);
    if (!raw) return;
    const { id, label } = JSON.parse(raw) as { id: string; label: string };
    onMove(id, label, path);
  };
  const onDragOver = (e: DragEvent) => {
    if (!canMove) return;
    e.preventDefault();
    e.stopPropagation();
    setDropActive(true);
  };

  // Folder rows (and the root) are drop targets that move a dragged tag into them.
  return (
    // biome-ignore lint/a11y/noStaticElementInteractions: folder/root is a drag-and-drop target for moving tags
    <div
      onDrop={canMove ? onDrop : undefined}
      onDragOver={canMove ? onDragOver : undefined}
      onDragLeave={() => setDropActive(false)}
      className={
        dropActive ? "rounded bg-indigo-500/10 ring-1 ring-indigo-500/40" : ""
      }
    >
      {Object.entries(node.folders).map(([name, child]) => {
        const childPath = path ? `${path}/${name}` : name;
        const isCollapsed = collapsed.has(childPath);
        const count = countLeaves(child);
        return (
          <div key={name}>
            <button
              type="button"
              onClick={() => toggle(childPath)}
              className="flex w-full items-center gap-1.5 py-1 text-left text-zinc-300 hover:text-zinc-100"
              style={pad}
            >
              <span className="text-zinc-500">{isCollapsed ? "▸" : "▾"}</span>
              <svg
                viewBox="0 0 24 24"
                className="h-4 w-4 text-amber-400/80"
                fill="currentColor"
                aria-hidden="true"
              >
                <path d="M10 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-8z" />
              </svg>
              {name}
              <span className="text-[10px] text-zinc-600">{count}</span>
            </button>
            {!isCollapsed && (
              <TreeView
                node={child}
                path={childPath}
                values={values}
                depth={depth + 1}
                collapsed={collapsed}
                toggle={toggle}
                onMove={onMove}
                canMove={canMove}
              />
            )}
          </div>
        );
      })}
      {node.leaves.map((leaf) => {
        const v = values.get(leaf.key);
        return (
          // biome-ignore lint/a11y/noStaticElementInteractions: tag row is draggable to move between folders
          <div
            key={leaf.id}
            draggable={canMove}
            onDragStart={(e) => {
              e.dataTransfer.setData(
                DRAG_TYPE,
                JSON.stringify({ id: leaf.id, label: leaf.label }),
              );
              e.dataTransfer.effectAllowed = "move";
            }}
            className={`flex items-center justify-between border-t border-white/5 py-1.5 ${canMove ? "cursor-grab active:cursor-grabbing" : ""}`}
            style={pad}
          >
            <span className="flex items-center gap-1.5 text-zinc-400">
              <span className="text-zinc-600">#</span>
              {leaf.label}
            </span>
            <span className="flex items-center gap-2">
              <span className="font-mono text-zinc-100">
                {v ? String(v.value) : <span className="text-zinc-600">—</span>}
              </span>
              {v && <Badge tone={qualityTone(v.quality)}>{v.quality}</Badge>}
            </span>
          </div>
        );
      })}
    </div>
  );
}

function countLeaves(n: TreeNode): number {
  return (
    n.leaves.length +
    Object.values(n.folders).reduce((s, f) => s + countLeaves(f), 0)
  );
}

/** Per-node tag folder tree (Obsidian-style paths). Live values + drag a tag between folders. */
export function TagsTree() {
  const qc = useQueryClient();
  const admin = isAdmin();
  const nodes = useQuery({ queryKey: ["nodes"], queryFn: api.listNodes });
  const [nodeId, setNodeId] = useState("");
  useEffect(() => {
    if (!nodeId && nodes.data?.length) setNodeId(nodes.data[0].id);
  }, [nodes.data, nodeId]);

  const tags = useQuery({
    queryKey: ["tags", nodeId],
    queryFn: () => api.listTags(nodeId),
    enabled: !!nodeId,
  });
  const values = useQuery({
    queryKey: ["values"],
    queryFn: api.currentValues,
    refetchInterval: 3000,
  });

  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const toggle = (p: string) =>
    setCollapsed((prev) => {
      const next = new Set(prev);
      next.has(p) ? next.delete(p) : next.add(p);
      return next;
    });
  const [moveMsg, setMoveMsg] = useState("");

  const move = useMutation({
    mutationFn: ({ id, name }: { id: string; name: string }) =>
      api.updateTag(id, { name }),
    onSuccess: (_d, v) => {
      setMoveMsg(`moved → ${v.name}`);
      qc.invalidateQueries({ queryKey: ["tags", nodeId] });
    },
    onError: (e) => setMoveMsg(`move failed: ${(e as Error).message}`),
  });
  const onMove = (id: string, label: string, targetPath: string) => {
    const newName = targetPath ? `${targetPath}/${label}` : label;
    move.mutate({ id, name: newName });
  };

  const valueMap = new Map<string, TagValue>(
    (values.data ?? []).map((v) => [v.tagKey, v]),
  );
  const tree = buildTree(tags.data ?? []);

  const [path, setPath] = useState("");
  const [name, setName] = useState("");
  const [dataType, setDataType] = useState("NUMBER");
  const create = useMutation({
    mutationFn: () =>
      api.createTag(nodeId, {
        name: path ? `${path}/${name}` : name,
        dataType,
      }),
    onSuccess: () => {
      setName("");
      qc.invalidateQueries({ queryKey: ["tags", nodeId] });
    },
  });

  // create a storage node (logical grouping + retention) — the historian's storage unit that tags and
  // flow "tag" nodes write into. Moved here from the (retired) Pipeline editor.
  const [nodeName, setNodeName] = useState("");
  const [retention, setRetention] = useState("24");
  const createNode = useMutation({
    mutationFn: () =>
      api.createNode({
        name: nodeName.trim(),
        retentionHours: Number(retention) || 24,
      }),
    onSuccess: (n: { id: string }) => {
      setNodeName("");
      qc.invalidateQueries({ queryKey: ["nodes"] });
      setNodeId(n.id); // switch to the freshly-created node
    },
  });

  const nodeSelect = (
    <select
      aria-label="node"
      className={inputClass}
      value={nodeId}
      onChange={(e) => setNodeId(e.target.value)}
    >
      {nodes.data?.map((n) => (
        <option key={n.id} value={n.id}>
          {n.name}
        </option>
      ))}
    </select>
  );

  const loadError = nodes.isError || tags.isError;
  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
      {loadError && (
        <ErrorNote error="Couldn't load tags from the server — retrying." />
      )}
      <div className="grid min-h-0 flex-1 gap-4 lg:grid-cols-3">
        <Panel
          title={`Tags · ${tags.data?.length ?? 0} — live values`}
          actions={nodeSelect}
          className="lg:col-span-2"
        >
          <div className="mb-2 flex items-center justify-between text-[11px] text-zinc-500">
            <span>
              {admin
                ? "drag a tag onto a folder to move it"
                : "read-only (ADMIN can move tags)"}
            </span>
            {moveMsg && <span className="text-zinc-400">{moveMsg}</span>}
          </div>
          <div className="text-sm">
            {tags.data && tags.data.length > 0 ? (
              <TreeView
                node={tree}
                path=""
                values={valueMap}
                depth={0}
                collapsed={collapsed}
                toggle={toggle}
                onMove={onMove}
                canMove={admin}
              />
            ) : (
              <span className="text-zinc-500">no tags in this node</span>
            )}
          </div>
        </Panel>

        {/* right rail: create forms, each its own panel */}
        <div className="grid min-h-0 grid-rows-2 gap-4">
          <Panel title="New node (storage group)">
            <div className="flex flex-col gap-2 text-sm">
              <input
                className={inputClass}
                placeholder="node name e.g. line1"
                value={nodeName}
                onChange={(e) => setNodeName(e.target.value)}
              />
              <input
                aria-label="retention hours"
                type="number"
                className={inputClass}
                placeholder="retention (h)"
                value={retention}
                onChange={(e) => setRetention(e.target.value)}
              />
              <Button
                disabled={!nodeName.trim() || !admin}
                onClick={() => createNode.mutate()}
              >
                Add node
              </Button>
              <span className="text-[11px] text-zinc-500">
                a node groups tags + sets time-machine retention
              </span>
              {createNode.isError && (
                <p className="text-xs text-rose-400">
                  {(createNode.error as Error).message}
                </p>
              )}
            </div>
          </Panel>

          <Panel title="New tag">
            <div className="flex flex-col gap-2 text-sm">
              <input
                className={inputClass}
                placeholder="folder path e.g. line2/sensors"
                value={path}
                onChange={(e) => setPath(e.target.value)}
              />
              <div className="flex gap-2">
                <input
                  className={`${inputClass} min-w-0 flex-1`}
                  placeholder="tag name"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                />
                <select
                  aria-label="data type"
                  className={inputClass}
                  value={dataType}
                  onChange={(e) => setDataType(e.target.value)}
                >
                  {["NUMBER", "STRING", "BOOL", "JSON"].map((t) => (
                    <option key={t}>{t}</option>
                  ))}
                </select>
              </div>
              <Button
                disabled={!nodeId || !name || !admin}
                onClick={() => create.mutate()}
              >
                Add tag
              </Button>
              {create.isError && (
                <p className="text-xs text-rose-400">
                  {(create.error as Error).message}
                </p>
              )}
            </div>
          </Panel>
        </div>
      </div>
    </div>
  );
}
