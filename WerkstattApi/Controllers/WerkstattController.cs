using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using WerkstattApi.Models;

namespace WerkstattApi.Controllers
{
    [Route("api/[controller]")]
    [ApiController]
    public class WerkstattController : ControllerBase
    {
        private readonly WerkstattApiContext _context;

        public WerkstattController(WerkstattApiContext context)
        {
            _context = context;
        }

        // GET: api/Werkstatt
        [HttpGet]
        public async Task<ActionResult<List<Werkstatt>>> GetWerkstattAsync()
        {
            return await _context.Werkstatt.ToListAsync();
        }

        // GET: api/Werkstatt/5
        [HttpGet("{id}")]
        public async Task<ActionResult<Werkstatt>> GetWerkstattAsync(int id)
        {
            var werkstatt = await _context.Werkstatt.FindAsync(id);

            if (werkstatt == null)
            {
                return NotFound();
            }

            return werkstatt;
        }

        // PUT: api/Werkstatt/5
        // To protect from overposting attacks, see https://go.microsoft.com/fwlink/?linkid=2123754
        [HttpPut("{id}")]
        public async Task<IActionResult> PutWerkstattAsync(int id, Werkstatt werkstatt)
        {
            if (id != werkstatt.Id)
            {
                return BadRequest();
            }

            _context.Entry(werkstatt).State = EntityState.Modified;

            try
            {
                await _context.SaveChangesAsync();
            }
            catch (DbUpdateConcurrencyException)
            {
                if (!WerkstattExists(id))
                {
                    return NotFound();
                }
                else
                {
                    throw;
                }
            }

            return NoContent();
        }

        // POST: api/Werkstatt
        // To protect from overposting attacks, see https://go.microsoft.com/fwlink/?linkid=2123754
        [HttpPost]
        public async Task<ActionResult<Werkstatt>> PostWerkstattAsync(Werkstatt werkstatt)
        {
            _context.Werkstatt.Add(werkstatt);
            await _context.SaveChangesAsync();

            return CreatedAtAction("GetWerkstattAsync", new { id = werkstatt.Id }, werkstatt);
        }

        // POST: api/Werkstatt/NoReturn
        [HttpPost("NoReturn")]
        public async Task<IActionResult> PostWerkstattNoReturnAsync(Werkstatt werkstatt)
        {
            _context.Werkstatt.Add(werkstatt);
            await _context.SaveChangesAsync();
            return Ok();
        }

        // DELETE: api/Werkstatt/5
        [HttpDelete("{id}")]
        public async Task<IActionResult> DeleteWerkstattAsync(int id)
        {
            var werkstatt = await _context.Werkstatt.FindAsync(id);
            if (werkstatt == null)
            {
                return NotFound();
            }

            _context.Werkstatt.Remove(werkstatt);
            await _context.SaveChangesAsync();

            return NoContent();
        }

        private bool WerkstattExists(int id)
        {
            return _context.Werkstatt.Any(e => e.Id == id);
        }
    }
}
