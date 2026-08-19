import type { MenuNode } from "@/api/types";

export interface SidebarMenuNode extends Omit<MenuNode, "children"> {
  children?: SidebarMenuNode[];
  navigationOnly?: boolean;
}

const HOME_MENU: SidebarMenuNode = {
  menuId: -1,
  parentId: 0,
  menuName: "首页",
  routerName: "Home",
  path: "/home",
  permission: "",
  menuType: 1,
  isButton: 0,
  sortNum: 0,
  metaInfo: "{}",
  children: []
};

function visibleClone(node: MenuNode): SidebarMenuNode | null {
  if (node.isButton === 1) return null;
  return {
    ...node,
    children: (node.children ?? [])
      .map(visibleClone)
      .filter((child): child is SidebarMenuNode => child !== null)
  };
}

function findByRouterName(
  nodes: SidebarMenuNode[],
  routerName: string
): SidebarMenuNode | undefined {
  for (const node of nodes) {
    if (node.routerName === routerName) return node;
    const nested = findByRouterName(node.children ?? [], routerName);
    if (nested) return nested;
  }
  return undefined;
}

function containerEntries(node?: SidebarMenuNode): SidebarMenuNode[] {
  if (!node) return [];
  return node.children?.length ? node.children : [node];
}

function dedupeByMenuId(nodes: SidebarMenuNode[]): SidebarMenuNode[] {
  const seen = new Set<number>();
  return nodes.filter(node => {
    if (seen.has(node.menuId)) return false;
    seen.add(node.menuId);
    return true;
  });
}

/**
 * Builds a concise navigation tree from the nodes authorized by IAM.
 * It never invents business permissions: synthetic nodes are navigation-only
 * containers, while every clickable business entry comes from /api/auth/menus.
 */
export function buildSidebarNavigation(menuTree: MenuNode[]): SidebarMenuNode[] {
  const visibleRoots = menuTree
    .map(visibleClone)
    .filter((node): node is SidebarMenuNode => node !== null);

  const knowledgeRoot = findByRouterName(visibleRoots, "Knowledge");
  const ingestRoot = findByRouterName(visibleRoots, "IngestTaskDir");
  const chat = findByRouterName(visibleRoots, "AiChat");
  const system = findByRouterName(visibleRoots, "SystemManage");

  const knowledgeEntries = dedupeByMenuId([
    ...containerEntries(knowledgeRoot),
    ...containerEntries(ingestRoot)
  ]);

  const navigation: SidebarMenuNode[] = [{ ...HOME_MENU }];
  if (knowledgeEntries.length) {
    navigation.push({
      menuId: -2,
      parentId: 0,
      menuName: "知识资产",
      routerName: "SidebarKnowledgeGroup",
      path: "/__navigation/knowledge",
      permission: "",
      menuType: 2,
      isButton: 0,
      sortNum: 10,
      metaInfo: "{}",
      navigationOnly: true,
      children: knowledgeEntries
    });
  }
  if (chat) navigation.push(chat);

  const groupedRoots = new Set([
    "Knowledge",
    "IngestTaskDir",
    "AiChat",
    "SystemManage"
  ]);
  navigation.push(
    ...visibleRoots.filter(root => !groupedRoots.has(root.routerName))
  );

  if (system?.children?.length) navigation.push(system);
  return navigation;
}

export function menuContainsPath(
  node: SidebarMenuNode,
  activePath: string
): boolean {
  if (node.path === activePath) return true;
  return (node.children ?? []).some(child =>
    menuContainsPath(child, activePath)
  );
}
