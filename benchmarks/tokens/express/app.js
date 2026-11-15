const express = require('express');

function create() {
  const db = new Map();
  let nextId = 1;
  const app = express();
  app.use(express.json());

  app.use('/todos', (req, res, next) => {
    if (req.get('Authorization') !== 'Bearer secret') {
      return res.status(401).send('Unauthorized');
    }
    next();
  });

  const find = (req, res) => {
    const todo = db.get(Number(req.params.id));
    if (!todo) res.status(404).send(`Todo ${req.params.id} not found`);
    return todo;
  };

  const valid = (req, res) => {
    const title = req.body && req.body.title;
    if (typeof title !== 'string' || !title.trim()) {
      res.status(400).send('title is required');
      return null;
    }
    return { title, done: !!req.body.done };
  };

  app.get('/todos', (req, res) => res.json([...db.values()]));
  app.get('/todos/:id', (req, res) => {
    const todo = find(req, res);
    if (todo) res.json(todo);
  });
  app.post('/todos', (req, res) => {
    const input = valid(req, res);
    if (!input) return;
    const todo = { id: nextId++, ...input };
    db.set(todo.id, todo);
    res.status(201).json(todo);
  });
  app.put('/todos/:id', (req, res) => {
    const old = find(req, res);
    if (!old) return;
    const input = valid(req, res);
    if (!input) return;
    const todo = { id: old.id, ...input };
    db.set(todo.id, todo);
    res.json(todo);
  });
  app.delete('/todos/:id', (req, res) => {
    const todo = find(req, res);
    if (!todo) return;
    db.delete(todo.id);
    res.status(204).end();
  });

  app.use((err, req, res, next) => res.status(400).send('Invalid JSON'));
  return app;
}

module.exports = { create };

if (require.main === module) create().listen(8080);
