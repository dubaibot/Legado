"""插件加载器：管理用户安装的 Python 插件模块的导入、重载和清理。

使用 importlib 按文件路径加载插件，避免 sys.path 污染和模块名冲突。
每个插件分配唯一模块名 plugin_<pluginId>，存入 _loaded_plugins 字典供检索。
"""

import importlib.util
import os
import sys

_plugin_dirs = []
_loaded_plugins = {}


def add_plugin_dir(dir_path):
    """将插件目录加入 sys.path（支持插件内部相对导入）。"""
    dir_path = str(dir_path)
    if dir_path not in sys.path:
        sys.path.append(dir_path)
    if dir_path not in _plugin_dirs:
        _plugin_dirs.append(dir_path)


def clear_plugin_modules():
    """清除 sys.modules 中的插件模块和 path_importer_cache 缓存。"""
    keys_to_remove = []
    for key in sys.modules:
        if key.startswith("plugin_"):
            keys_to_remove.append(key)
    for key in keys_to_remove:
        del sys.modules[key]

    for d in _plugin_dirs:
        sys.path_importer_cache.pop(d, None)

    _loaded_plugins.clear()


def load_plugin(plugin_id, file_path):
    """按文件路径加载单个插件模块。"""
    module_name = "plugin_" + plugin_id
    spec = importlib.util.spec_from_file_location(module_name, file_path)
    if spec is None or spec.loader is None:
        raise ImportError("Cannot load plugin: " + file_path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    _loaded_plugins[plugin_id] = module
    return module


def get_plugin(plugin_id):
    """获取已加载的插件模块，未加载返回 None。"""
    return _loaded_plugins.get(plugin_id)


def is_plugin_loaded(plugin_id):
    """检查插件是否已加载。"""
    return plugin_id in _loaded_plugins


def reload_all(plugin_dir):
    """清空并重新加载 plugin_dir 下所有插件。

    返回已加载插件的字典副本。
    单个插件加载失败不影响其他插件。
    """
    add_plugin_dir(plugin_dir)
    clear_plugin_modules()

    plugin_dir = str(plugin_dir)
    if not os.path.isdir(plugin_dir):
        return {}

    for entry in os.listdir(plugin_dir):
        plugin_path = os.path.join(plugin_dir, entry, "plugin.py")
        if os.path.isfile(plugin_path):
            try:
                load_plugin(entry, plugin_path)
            except Exception as e:
                print("Failed to load plugin " + entry + ": " + str(e))

    return dict(_loaded_plugins)
