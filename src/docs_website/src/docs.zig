const std = @import("std");
const Allocator = std.mem.Allocator;
const assert = std.debug.assert;
const LazyPath = std.Build.LazyPath;
const log = std.log.scoped(.docs);
const Website = @import("website.zig").Website;
const assets = @import("assets.zig");
const Html = @import("html.zig").Html;

const enforce_readme_md = false;

const SearchIndexEntry = struct {
    page_path: []const u8,
    html_path: LazyPath,
};
const SearchIndex = std.ArrayList(SearchIndexEntry);

pub fn build(
    b: *std.Build,
    website: Website,
    source_dir: []const u8,
) !LazyPath {
    const arena = b.allocator;
    const docs = b.addWriteFiles();

    var search_index = SearchIndex.init(arena);

    const root_menu = try create_root_menu(arena, "TigerBeetle Docs", source_dir);
    try root_menu.install(b, website, &root_menu, docs, &search_index);

    const search_index_writer_exe = b.addExecutable(.{
        .name = "search_index_writer",
        .root_source_file = b.path("src/search_index_writer.zig"),
        .target = b.graph.host,
    });
    const run_search_index_writer = b.addRunArtifact(search_index_writer_exe);
    const search_index_output = run_search_index_writer.addOutputFileArg("search-index.json");
    _ = docs.addCopyFile(search_index_output, "search-index.json");
    for (search_index.items) |entry| {
        run_search_index_writer.addArg(entry.page_path);
        run_search_index_writer.addFileArg(entry.html_path);
    }

    return docs.getDirectory();
}

fn create_root_menu(arena: std.mem.Allocator, title: []const u8, base_path: []const u8) !Menu {
    var pages = std.ArrayList(DocPage).init(arena);
    var menus = std.ArrayList(Menu).init(arena);

    const index_path = try std.fs.path.join(arena, &.{ base_path, "README.md" });
    const index_page = try DocPage.init(arena, base_path, index_path);
    const quick_start_path = try std.fs.path.join(arena, &.{ base_path, "quick-start.md" });
    try pages.append(try DocPage.init(arena, base_path, quick_start_path));

    var dir = std.fs.cwd().openDir(base_path, .{ .iterate = true }) catch |err| {
        log.err("unable to open path '{s}'", .{base_path});
        return err;
    };
    defer dir.close();

    var it = dir.iterate();
    while (try it.next()) |entry| {
        if (entry.kind == .directory) {
            const menu_path = try std.fs.path.join(arena, &.{ base_path, entry.name });
            const menu_title = try make_title(arena, entry.name);
            try menus.append(try DocPage.find_all(arena, menu_title, base_path, menu_path));
        }
    }

    try menus.append(try create_clients_menu(arena));

    std.mem.sort(DocPage, pages.items, {}, DocPage.asc);
    std.mem.sort(Menu, menus.items, {}, Menu.asc);

    return .{
        .title = title,
        .index_page = index_page,
        .menus = menus.items,
        .pages = pages.items,
    };
}

fn create_clients_menu(arena: std.mem.Allocator) !Menu {
    var pages = std.ArrayList(DocPage).init(arena);

    const clients = &.{ "go", "java", "dotnet", "node", "python" };
    const titles = &.{ "Go", "Java", ".NET", "Node.js", "Python" };
    inline for (clients, titles) |client, title| {
        try pages.append(.{
            .path_source = "../clients/" ++ client ++ "/README.md",
            .path_target = "clients/" ++ client,
            .title = title,
        });
    }

    return .{
        .title = "Clients",
        .index_page = null,
        .menus = &.{},
        .pages = pages.items,
    };
}

const Menu = struct {
    title: []const u8,
    index_page: ?DocPage,
    menus: []Menu,
    pages: []DocPage,

    fn asc(context: void, lhs: Menu, rhs: Menu) bool {
        _ = context;
        assert(!std.mem.eql(u8, lhs.title, rhs.title));
        return std.mem.lessThan(u8, lhs.title, rhs.title);
    }

    fn contains_page(self: *const Menu, target: *const DocPage) bool {
        for (self.menus) |menu| {
            if (menu.contains_page(target)) return true;
        }
        for (self.pages) |*page| {
            if (page == target) return true;
        }
        return false;
    }

    fn write_links(
        self: *const Menu,
        website: Website,
        html: *Html,
        page_target: *const DocPage,
    ) !void {
        try html.write("<ol>", .{});
        for (self.menus) |menu| {
            try html.write("<li><details", .{});
            if (menu.contains_page(page_target)) try html.write(" open", .{});
            try html.write("><summary class=\"item\">", .{});
            if (menu.index_page) |*page| {
                try html.write(
                    \\<a href="$url_prefix/$url/">$title</a>
                , .{
                    .url_prefix = website.url_prefix,
                    .url = page.path_target,
                    .title = try html.from_md(menu.title), // Fabio: index page titles are too long
                });
            } else {
                try html.write("$title", .{
                    .title = try html.from_md(menu.title),
                });
            }
            try html.write("</summary>", .{});
            try menu.write_links(website, html, page_target);
            try html.write("</details></li>", .{});
        }
        for (self.pages) |*page| {
            try html.write(
                \\<li class="item"><a href="$url_prefix/$url/"$page_target>$title</a></li>
            , .{
                .url_prefix = website.url_prefix,
                .url = page.path_target,
                .page_target = if (page == page_target) " class=\"target\"" else "",
                .title = try html.from_md(page.title),
            });
        }
        try html.write("</ol>", .{});
    }

    fn install(
        self: *const Menu,
        b: *std.Build,
        website: Website,
        root_menu: *const Menu,
        docs: *std.Build.Step.WriteFile,
        search_index: *SearchIndex,
    ) !void {
        if (self.index_page) |index_page| {
            try index_page.install(b, website, root_menu, docs, search_index);
        }
        for (self.menus) |*menu| {
            try menu.install(b, website, root_menu, docs, search_index);
        }
        for (self.pages) |*page| {
            try page.install(b, website, root_menu, docs, search_index);
        }
    }
};

const DocPage = struct {
    path_source: []const u8,
    path_target: []const u8,

    // Parsed from Markdown content.
    title: []const u8,

    fn init(arena: Allocator, base_path: []const u8, path_source: []const u8) !DocPage {
        assert(std.mem.endsWith(u8, path_source, ".md"));

        var path_target = path_source[base_path.len + 1 ..];
        if (std.mem.eql(u8, path_target, "README.md")) {
            path_target = ".";
        } else if (std.mem.endsWith(u8, path_target, "/README.md")) {
            path_target = path_target[0 .. path_target.len - "/README.md".len];
        } else {
            path_target = path_target[0 .. path_target.len - ".md".len];
        }

        var post: DocPage = .{
            .path_source = path_source,
            .path_target = path_target,
            .title = undefined,
        };
        try post.load(arena);

        return post;
    }

    fn load(self: *DocPage, arena: Allocator) !void {
        errdefer log.err("error while loading '{s}'", .{self.path_source});

        const source = try std.fs.cwd().readFileAlloc(
            arena,
            self.path_source,
            Website.file_size_max,
        );
        var line_it = std.mem.tokenizeScalar(u8, source, '\n');

        const title_line = line_it.next().?;
        if (title_line.len < 3 or !std.mem.eql(u8, title_line[0..2], "# ")) {
            return error.TitleInvalid;
        }
        self.title = title_line[2..];
    }

    fn asc(context: void, lhs: DocPage, rhs: DocPage) bool {
        _ = context;
        assert(!std.mem.eql(u8, lhs.title, rhs.title));
        return std.mem.lessThan(u8, lhs.title, rhs.title);
    }

    fn find_all(
        arena: Allocator,
        title: []const u8,
        base_path: []const u8,
        path: []const u8,
    ) !Menu {
        var index_page: ?DocPage = null;
        var pages = std.ArrayList(DocPage).init(arena);
        var menus = std.ArrayList(Menu).init(arena);

        var dir = std.fs.cwd().openDir(path, .{ .iterate = true }) catch |err| {
            log.err("unable to open path '{s}'", .{path});
            return err;
        };
        defer dir.close();

        var it = dir.iterate();
        while (try it.next()) |entry| {
            if (entry.kind == .file and std.mem.endsWith(u8, entry.name, ".md")) {
                const page_path = try std.fs.path.join(arena, &.{ path, entry.name });
                const page = try DocPage.init(arena, base_path, page_path);
                if (std.mem.eql(u8, entry.name, "README.md")) {
                    assert(index_page == null);
                    index_page = page;
                } else {
                    try pages.append(page);
                }
            } else if (entry.kind == .directory) {
                if (std.mem.eql(u8, entry.name, "internals")) continue; // hide internals
                const menu_path = try std.fs.path.join(arena, &.{ path, entry.name });
                const menu_title = try make_title(arena, entry.name);
                try menus.append(try find_all(arena, menu_title, base_path, menu_path));
            }
        }

        if (enforce_readme_md and index_page == null) {
            log.err("README.md not found in '{s}'", .{path});
            return error.MissingReadmeMd;
        }

        std.mem.sort(DocPage, pages.items, {}, DocPage.asc);
        std.mem.sort(Menu, menus.items, {}, Menu.asc);

        return .{
            .title = title,
            .index_page = index_page,
            .menus = menus.items,
            .pages = pages.items,
        };
    }

    fn install(
        self: *const DocPage,
        b: *std.Build,
        website: Website,
        root_menu: *const Menu,
        docs: *std.Build.Step.WriteFile,
        search_index: *SearchIndex,
    ) !void {
        const pandoc_step = std.Build.Step.Run.create(b, "run pandoc");
        pandoc_step.addFileArg(website.pandoc_bin);
        pandoc_step.addArgs(&.{ "--from", "gfm", "--to", "html5" });
        pandoc_step.addArg("--lua-filter");
        pandoc_step.addFileArg(b.path("pandoc/markdown-links.lua"));
        pandoc_step.addArg("--lua-filter");
        pandoc_step.addFileArg(b.path("pandoc/anchor-links.lua"));
        pandoc_step.addArg("--lua-filter");
        pandoc_step.addFileArg(b.path("pandoc/table-wrapper.lua"));
        pandoc_step.addArg("--output");
        const pandoc_out = pandoc_step.addOutputFileArg("pandoc-out.html");
        pandoc_step.addFileArg(b.path(self.path_source));

        try search_index.append(.{ .page_path = self.path_target, .html_path = pandoc_out });

        const title_suffix = "TigerBeetle Docs";
        const page_title = blk: {
            if (std.mem.eql(u8, self.title, title_suffix)) {
                break :blk self.title;
            }
            break :blk try std.mem.join(b.allocator, " | ", &.{ self.title, title_suffix });
        };

        const nav_html = try Html.create(b.allocator);
        try root_menu.write_links(website, nav_html, self);

        const page_path = website.write_page(.{
            .title = page_title,
            .nav = nav_html.string(),
            .content = pandoc_out,
        });
        _ = docs.addCopyFile(page_path, b.pathJoin(&.{ self.path_target, "index.html" }));

        // If it exists, copy the page's asset directory.
        const page_dir = self.path_source[0 .. self.path_source.len - ".md".len];
        if (try path_exists(b.pathFromRoot(page_dir))) {
            _ = docs.addCopyDirectory(b.path(page_dir), self.path_target, .{
                .include_extensions = &assets.supported_file_types,
            });
        }
    }
};

fn make_title(arena: Allocator, input: []const u8) ![]const u8 {
    const output = try arena.dupe(u8, input);
    var needs_upper = true;
    for (output) |*c| {
        if (needs_upper) {
            c.* = std.ascii.toUpper(c.*);
            needs_upper = false;
        }
        switch (c.*) {
            ' ' => needs_upper = true,
            else => {},
        }
    }
    return output;
}

fn path_exists(path: []const u8) !bool {
    std.fs.cwd().access(path, .{}) catch |err| switch (err) {
        error.FileNotFound => return false,
        else => return err,
    };
    return true;
}
